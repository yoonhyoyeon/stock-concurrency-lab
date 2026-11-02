package com.stocklab.admin;

public class ResetRejectedException extends RuntimeException {

    public ResetRejectedException(int inFlight) {
        super("reset rejected: " + inFlight + " request(s) in flight");
    }
}
