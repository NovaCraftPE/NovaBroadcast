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

/**
 * Clean-room answering-side WebRTC backend using the general-purpose
 * dev.onvoid webrtc-java JNI wrapper. No broadcaster-specific implementation
 * code or constants are used here.
 */
final class WebRtcPeerBackend implements NetherNetSignalingServer.PeerBackend, AutoCloseable {
    private static final Duration SDP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration ICE_TIMEOUT = Duration.ofSeconds(20);

    private final AppConfig config;
    private final AudioDeviceModule audioModule;
    private final PeerConnectionFactory factory;
    private final NetherNetIdentity identity;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    WebRtcPeerBackend(AppConfig config) throws Exception {
        this.config = Objects.requireNonNull(config);
        // NovaBroadcast carries data channels only. Using WebRTC's dummy audio
        // layer avoids opening PulseAudio/ALSA devices on headless servers.
        this.audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        this.factory = new PeerConnectionFactory(audioModule);
        this.identity = NetherNetIdentity.loadOrCreate(
                Path.of(config.netherNetIdentityKey()), config.netherNetIdentityDomain());
        System.out.println("[NetherNet] Operator identity: " + identity.publicKeyFingerprint());
    }

    @Override
    public boolean ready() {
        return !closed;
    }

    @Override
    public String answer(String networkId, String offerSdp) throws Exception {
        if (closed) throw new IllegalStateException("WebRTC backend is closed");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(offerSdp, "offerSdp");

        Peer replacement = new Peer(networkId);
        Peer old = peers.put(networkId, replacement);
        if (old != null) old.close();

        try {
            return replacement.answer(offerSdp);
        } catch (Exception e) {
            peers.remove(networkId, replacement);
            replacement.close();
            throw e;
        }
    }

    void sendReliable(String networkId, byte[] applicationPayload) throws Exception {
        Peer peer = requirePeer(networkId);
        peer.sendReliable(applicationPayload);
    }

    boolean sendUnreliable(String networkId, byte[] applicationPayload) throws Exception {
        Peer peer = requirePeer(networkId);
        return peer.sendUnreliable(applicationPayload);
    }

    private Peer requirePeer(String networkId) {
        Peer peer = peers.get(networkId);
        if (peer == null) throw new IllegalStateException("No active WebRTC peer for NetworkID " + networkId);
        return peer;
    }

    @Override
    public void close() {
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
        private final NetherNetFraming.ReliableReassembler reliableReassembler =
                new NetherNetFraming.ReliableReassembler();
        private final RTCPeerConnection connection;
        private volatile RTCDataChannel reliable;
        private volatile RTCDataChannel unreliable;
        private volatile boolean peerClosed;

        Peer(String networkId) {
            this.networkId = networkId;
            this.connection = factory.createPeerConnection(peerConfiguration(), new Observer());
            if (connection == null) throw new IllegalStateException("WebRTC peer creation returned null");
        }

        String answer(String offerSdp) throws Exception {
            NetherNetIdentity.Offer offer = NetherNetIdentity.stripClientIdentity(offerSdp);
            if (offer.hasIdentity()) {
                System.out.println("[NetherNet] Client identity assertion present for NetworkID " + networkId +
                        " (cryptographic client-token validation is not enabled yet)");
            } else {
                System.out.println("[NetherNet] Client offer has no identity assertion for NetworkID " + networkId);
            }

            // Mojang requires a=identity to be removed before handing the SDP to
            // the underlying WebRTC implementation.
            await(setRemote(new RTCSessionDescription(RTCSdpType.OFFER, offer.cleanSdp())), SDP_TIMEOUT,
                    "set remote SDP");

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
            if (channel.getState() != RTCDataChannelState.OPEN) {
                throw new IllegalStateException(label + " is not open: " + channel.getState());
            }
            return channel;
        }

        private CompletableFuture<RTCSessionDescription> createAnswer() {
            CompletableFuture<RTCSessionDescription> result = new CompletableFuture<>();
            connection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription description) { result.complete(description); }
                @Override public void onFailure(String error) {
                    result.completeExceptionally(new IllegalStateException("createAnswer failed: " + error));
                }
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
                @Override public void onFailure(String error) {
                    future.completeExceptionally(new IllegalStateException(action + " failed: " + error));
                }
            };
        }

        private void attach(RTCDataChannel channel) {
            String label = channel.getLabel();
            if (NetherNetTransport.RELIABLE_CHANNEL.equals(label)) {
                reliable = channel;
            } else if (NetherNetTransport.UNRELIABLE_CHANNEL.equals(label)) {
                unreliable = channel;
            } else {
                System.out.println("[NetherNet] Ignoring unexpected data channel: " + label);
                channel.close();
                channel.dispose();
                return;
            }

            channel.registerObserver(new RTCDataChannelObserver() {
                @Override public void onBufferedAmountChange(long previousAmount) {}

                @Override public void onStateChange() {
                    System.out.println("[NetherNet] " + networkId + " " + label + " -> " + channel.getState());
                }

                @Override public void onMessage(RTCDataChannelBuffer buffer) {
                    ByteBuffer source = buffer.data.duplicate();
                    byte[] frame = new byte[source.remaining()];
                    source.get(frame);
                    try {
                        if (NetherNetTransport.RELIABLE_CHANNEL.equals(label)) {
                            reliableReassembler.accept(frame).ifPresent(payload -> onBedrockPayload(true, payload));
                        } else {
                            onBedrockPayload(false, NetherNetFraming.stripUnreliable(frame));
                        }
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
                BedrockWireInspector.Inspection value = inspection.get();
                BedrockWireInspector.PacketHeader header = value.header();
                System.out.println("[Bedrock] " + networkId + " " + lane +
                        " shape=" + value.shape() +
                        " packetId=" + header.packetId() +
                        " senderSubClient=" + header.senderSubClientId() +
                        " targetSubClient=" + header.targetSubClientId() +
                        " bytes=" + payload.length);
            } else {
                System.out.println("[Bedrock] " + networkId + " " + lane +
                        " unrecognized/enveloped payload bytes=" + payload.length);
            }
        }

        @Override
        public void close() {
            if (peerClosed) return;
            peerClosed = true;
            closeChannel(reliable);
            closeChannel(unreliable);
            connection.close();
        }

        private void closeChannel(RTCDataChannel channel) {
            if (channel == null) return;
            try { channel.unregisterObserver(); } catch (Exception ignored) {}
            try { channel.close(); } catch (Exception ignored) {}
            try { channel.dispose(); } catch (Exception ignored) {}
        }

        private final class Observer implements PeerConnectionObserver {
            @Override public void onIceCandidate(RTCIceCandidate candidate) {
                // Non-trickle HTTP signaling: candidates are returned in the final local SDP.
            }

            @Override public void onIceGatheringChange(RTCIceGatheringState state) {
                if (state == RTCIceGatheringState.COMPLETE) iceComplete.complete(null);
            }

            @Override public void onConnectionChange(RTCPeerConnectionState state) {
                System.out.println("[NetherNet] " + networkId + " peer -> " + state);
                if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                    peers.remove(networkId, Peer.this);
                }
            }

            @Override public void onDataChannel(RTCDataChannel dataChannel) {
                attach(dataChannel);
            }
        }
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout, String action) throws Exception {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(action + " timed out after " + timeout.toSeconds() + " seconds", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(action + " failed", cause);
        }
    }
}
