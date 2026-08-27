package uk.blazecraft.novabroadcast;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/** Clean-room answering-side native WebRTC backend. */
final class WebRtcPeerBackend implements NetherNetSignalingServer.PeerBackend, AutoCloseable {
    private static final Duration SDP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration ICE_TIMEOUT = Duration.ofSeconds(20);

    private final AppConfig config;
    private final AudioDeviceModule audioModule;
    private final PeerConnectionFactory factory;
    private final NetherNetIdentity identity;
    private final ClientIdentityVerifier clientVerifier;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    WebRtcPeerBackend(AppConfig config) throws Exception {
        this.config = Objects.requireNonNull(config);
        this.audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        this.factory = new PeerConnectionFactory(audioModule);
        this.identity = NetherNetIdentity.loadOrCreate(
                Path.of(config.netherNetIdentityKey()), config.netherNetIdentityDomain());
        this.clientVerifier = config.netherNetClientJwksUrl().isBlank()
                ? null : new ClientIdentityVerifier(config.netherNetClientJwksUrl());
        if (config.netherNetRequireClientIdentity() && clientVerifier == null) {
            throw new IllegalStateException(
                    "nethernet.requireClientIdentity=true requires nethernet.clientJwksUrl");
        }
        System.out.println("[NetherNet] Operator identity: " + identity.publicKeyFingerprint());
        System.out.println("[NetherNet] Client identity verification: " +
                (clientVerifier == null ? "disabled" : "enabled"));
    }

    @Override public boolean ready() { return !closed; }

    @Override
    public String answer(String networkId, String offerSdp) throws Exception {
        if (closed) throw new IllegalStateException("WebRTC backend is closed");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(offerSdp, "offerSdp");

        NetherNetIdentity.Offer offer = NetherNetIdentity.stripClientIdentity(offerSdp);
        authenticateClient(networkId, offerSdp, offer);

        // Authentication happens before allocating ICE/DTLS/SCTP state.
        Peer replacement = new Peer(networkId);
        Peer old = peers.put(networkId, replacement);
        if (old != null) old.close();
        try {
            return replacement.answer(offer.cleanSdp());
        } catch (Exception e) {
            peers.remove(networkId, replacement);
            replacement.close();
            throw e;
        }
    }

    private void authenticateClient(String networkId, String originalOffer,
                                    NetherNetIdentity.Offer offer) throws Exception {
        if (!offer.hasIdentity()) {
            if (config.netherNetRequireClientIdentity()) {
                throw new SecurityException("Client identity is required");
            }
            System.out.println("[NetherNet] Unauthenticated client offer for NetworkID " + networkId);
            return;
        }
        if (clientVerifier == null) {
            if (config.netherNetRequireClientIdentity()) {
                throw new SecurityException("Client identity cannot be verified without configured JWKS");
            }
            System.out.println("[NetherNet] Client assertion present but verification disabled for NetworkID " + networkId);
            return;
        }
        ClientIdentityVerifier.VerifiedClient client = clientVerifier.verify(originalOffer, offer.encodedIdentity());
        System.out.println("[NetherNet] Verified client NetworkID=" + networkId +
                (client.xuid().isBlank() ? "" : " XUID=" + client.xuid()) +
                (client.uuid().isBlank() ? "" : " UUID=" + client.uuid()));
    }

    void sendReliable(String networkId, byte[] payload) throws Exception { requirePeer(networkId).sendReliable(payload); }
    boolean sendUnreliable(String networkId, byte[] payload) throws Exception { return requirePeer(networkId).sendUnreliable(payload); }

    private Peer requirePeer(String networkId) {
        Peer peer = peers.get(networkId);
        if (peer == null) throw new IllegalStateException("No active WebRTC peer for NetworkID " + networkId);
        return peer;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        peers.values().forEach(Peer::close);
        peers.clear();
        factory.dispose();
        audioModule.dispose();
    }

    private RTCConfiguration peerConfiguration() {
        RTCConfiguration rtc = new RTCConfiguration();
        if (!config.netherNetStunUrl().isBlank()) {
            RTCIceServer stun = new RTCIceServer();
            stun.urls.add(config.netherNetStunUrl());
            rtc.iceServers.add(stun);
        }
        rtc.portAllocatorConfig.minPort = config.netherNetIceMinPort();
        rtc.portAllocatorConfig.maxPort = config.netherNetIceMaxPort();
        rtc.portAllocatorConfig.setDisableTcp(true);
        return rtc;
    }

    private final class Peer implements AutoCloseable {
        private final String networkId;
        private final CompletableFuture<Void> iceComplete = new CompletableFuture<>();
        private final NetherNetFraming.ReliableReassembler reliableReassembler = new NetherNetFraming.ReliableReassembler();
        private final RTCPeerConnection connection;
        private volatile RTCDataChannel reliable;
        private volatile RTCDataChannel unreliable;
        private volatile boolean peerClosed;

        Peer(String networkId) {
            this.networkId = networkId;
            this.connection = factory.createPeerConnection(peerConfiguration(), new Observer());
            if (connection == null) throw new IllegalStateException("WebRTC peer creation returned null");
        }

        String answer(String cleanOfferSdp) throws Exception {
            await(setRemote(new RTCSessionDescription(RTCSdpType.OFFER, cleanOfferSdp)), SDP_TIMEOUT, "set remote SDP");
            RTCSessionDescription answer = await(createAnswer(), SDP_TIMEOUT, "create SDP answer");
            await(setLocal(answer), SDP_TIMEOUT, "set local SDP");
            if (connection.getIceGatheringState() != RTCIceGatheringState.COMPLETE) {
                await(iceComplete, ICE_TIMEOUT, "ICE candidate gathering");
            }
            RTCSessionDescription local = connection.getLocalDescription();
            if (local == null || local.sdp == null || local.sdp.isBlank()) {
                throw new IllegalStateException("WebRTC produced no local SDP answer");
            }
            String signedAnswer = identity.signAnswer(local.sdp);
            System.out.println("[NetherNet] Signed SDP answer ready for NetworkID " + networkId);
            return signedAnswer;
        }

        void sendReliable(byte[] payload) throws Exception {
            Objects.requireNonNull(payload, "payload");
            RTCDataChannel channel = requireOpenChannel(reliable, NetherNetTransport.RELIABLE_CHANNEL);
            for (byte[] frame : NetherNetFraming.frameReliable(payload, config.netherNetMaxSctpMessageSize())) {
                channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(frame), true));
            }
        }

        boolean sendUnreliable(byte[] payload) throws Exception {
            Objects.requireNonNull(payload, "payload");
            RTCDataChannel channel = requireOpenChannel(unreliable, NetherNetTransport.UNRELIABLE_CHANNEL);
            var framed = NetherNetFraming.frameUnreliable(payload, config.netherNetMaxSctpMessageSize());
            if (framed.isEmpty()) return false;
            channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(framed.get()), true));
            return true;
        }

        private RTCDataChannel requireOpenChannel(RTCDataChannel channel, String label) {
            if (channel == null) throw new IllegalStateException(label + " has not been received yet");
            if (channel.getState() != RTCDataChannelState.OPEN) throw new IllegalStateException(label + " is not open: " + channel.getState());
            return channel;
        }

        private CompletableFuture<RTCSessionDescription> createAnswer() {
            CompletableFuture<RTCSessionDescription> result = new CompletableFuture<>();
            connection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription d) { result.complete(d); }
                @Override public void onFailure(String error) { result.completeExceptionally(new IllegalStateException("createAnswer failed: " + error)); }
            });
            return result;
        }

        private CompletableFuture<Void> setRemote(RTCSessionDescription sdp) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            connection.setRemoteDescription(sdp, setObserver(result, "setRemoteDescription"));
            return result;
        }
        private CompletableFuture<Void> setLocal(RTCSessionDescription sdp) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            connection.setLocalDescription(sdp, setObserver(result, "setLocalDescription"));
            return result;
        }
        private SetSessionDescriptionObserver setObserver(CompletableFuture<Void> future, String action) {
            return new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { future.complete(null); }
                @Override public void onFailure(String error) { future.completeExceptionally(new IllegalStateException(action + " failed: " + error)); }
            };
        }

        private void attach(RTCDataChannel channel) {
            String label = channel.getLabel();
            if (NetherNetTransport.RELIABLE_CHANNEL.equals(label)) reliable = channel;
            else if (NetherNetTransport.UNRELIABLE_CHANNEL.equals(label)) unreliable = channel;
            else { channel.close(); channel.dispose(); return; }
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override public void onBufferedAmountChange(long previousAmount) {}
                @Override public void onStateChange() { System.out.println("[NetherNet] " + networkId + " " + label + " -> " + channel.getState()); }
                @Override public void onMessage(RTCDataChannelBuffer buffer) {
                    ByteBuffer source = buffer.data.duplicate();
                    byte[] frame = new byte[source.remaining()]; source.get(frame);
                    try {
                        if (NetherNetTransport.RELIABLE_CHANNEL.equals(label))
                            reliableReassembler.accept(frame).ifPresent(payload -> onBedrockPayload(true, payload));
                        else onBedrockPayload(false, NetherNetFraming.stripUnreliable(frame));
                    } catch (RuntimeException e) {
                        System.err.println("[NetherNet] Invalid " + label + " frame from " + networkId + ": " + e.getMessage());
                    }
                }
            });
            System.out.println("[NetherNet] Accepted data channel " + label + " for " + networkId);
        }

        private void onBedrockPayload(boolean reliableChannel, byte[] payload) {
            String lane = reliableChannel ? "reliable" : "unreliable";
            var inspection = BedrockWireInspector.inspect(payload);
            if (inspection.isPresent()) {
                var value = inspection.get(); var header = value.header();
                System.out.println("[Bedrock] " + networkId + " " + lane + " shape=" + value.shape() +
                        " packetId=" + header.packetId() + " senderSubClient=" + header.senderSubClientId() +
                        " targetSubClient=" + header.targetSubClientId() + " bytes=" + payload.length);
            } else System.out.println("[Bedrock] " + networkId + " " + lane + " unrecognized/enveloped payload bytes=" + payload.length);
        }

        @Override public void close() {
            if (peerClosed) return; peerClosed = true;
            closeChannel(reliable); closeChannel(unreliable); connection.close();
        }
        private void closeChannel(RTCDataChannel channel) {
            if (channel == null) return;
            try { channel.unregisterObserver(); } catch (Exception ignored) {}
            try { channel.close(); } catch (Exception ignored) {}
            try { channel.dispose(); } catch (Exception ignored) {}
        }
        private final class Observer implements PeerConnectionObserver {
            @Override public void onIceCandidate(RTCIceCandidate candidate) {}
            @Override public void onIceGatheringChange(RTCIceGatheringState state) { if (state == RTCIceGatheringState.COMPLETE) iceComplete.complete(null); }
            @Override public void onConnectionChange(RTCPeerConnectionState state) {
                System.out.println("[NetherNet] " + networkId + " peer -> " + state);
                if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) peers.remove(networkId, Peer.this);
            }
            @Override public void onDataChannel(RTCDataChannel dataChannel) { attach(dataChannel); }
        }
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout, String action) throws Exception {
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { throw new IllegalStateException(action + " timed out after " + timeout.toSeconds() + " seconds", e); }
        catch (ExecutionException e) {
            Throwable cause = e.getCause(); if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(action + " failed", cause);
        }
    }
}
