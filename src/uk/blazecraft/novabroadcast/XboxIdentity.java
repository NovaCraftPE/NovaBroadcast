package uk.blazecraft.novabroadcast;

record XboxIdentity(
        String userHash,
        String xstsToken,
        String xuid,
        String gamertag,
        String authorizationHeader) {}
