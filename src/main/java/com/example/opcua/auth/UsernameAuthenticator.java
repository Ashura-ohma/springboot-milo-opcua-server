package com.example.opcua.auth;

import lombok.Value;

public interface UsernameAuthenticator extends Authenticator<UsernameAuthenticator.Credentials> {

    @Value
    class Credentials {

        String username;
        String password;
    }
}
