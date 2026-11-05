package com.anitec.backend.identity.application;
/** Bounded asynchronous acceptance; deliberately not a durable delivery guarantee. */
public interface PasswordRecoveryWorkQueue { void submit(Runnable work); }
