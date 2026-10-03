package com.example.opcua.auth;

public interface Authenticator<T> {

    boolean validateToken(T token);
}

