package com.gathin.deviceinfo;

/** State only: inspection, restore, permission return and preparation never launch an installer. */
final class ApkInstallSession {
    enum Phase { IDLE, PICKING, PREPARING, READY, INSTALLING }
    static final class Prepared {
        final String token;
        final String sourceName;
        final String packageName;
        final String version;
        final long bytes;
        Prepared(String token, String sourceName, String packageName, String version, long bytes) {
            this.token = token; this.sourceName = sourceName; this.packageName = packageName;
            this.version = version; this.bytes = bytes;
        }
    }

    private Phase phase = Phase.IDLE;
    private long generation;
    private Prepared item;
    private String error;
    private boolean expectsResult;

    static ApkInstallSession restore(String savedPhase, boolean expectsResult) {
        ApkInstallSession session = new ApkInstallSession();
        if (Phase.PICKING.name().equals(savedPhase)) session.phase = Phase.PICKING;
        if (Phase.INSTALLING.name().equals(savedPhase)) {
            session.phase = Phase.INSTALLING;
            session.expectsResult = expectsResult;
        }
        return session;
    }

    synchronized Phase phase() { return phase; }
    synchronized Prepared item() { return item; }
    synchronized String error() { return error; }
    synchronized boolean expectsResult() { return expectsResult; }

    synchronized boolean pick() {
        if (phase != Phase.IDLE) return false;
        phase = Phase.PICKING;
        error = null;
        return true;
    }

    synchronized void cancelPicker() { if (phase == Phase.PICKING) phase = Phase.IDLE; }

    synchronized long prepare() {
        if (phase != Phase.IDLE && phase != Phase.PICKING) return 0;
        phase = Phase.PREPARING;
        item = null;
        error = null;
        return ++generation;
    }

    synchronized boolean isPreparing(long ticket) { return phase == Phase.PREPARING && generation == ticket; }

    synchronized boolean complete(long ticket, Prepared prepared) {
        if (!isPreparing(ticket) || prepared == null) return false;
        item = prepared;
        phase = Phase.READY;
        return true;
    }

    synchronized void failed(long ticket, String message) {
        if (!isPreparing(ticket)) return;
        phase = Phase.IDLE;
        error = message;
    }

    synchronized boolean launch(boolean resultContract) {
        if (phase != Phase.READY || item == null) return false;
        phase = Phase.INSTALLING;
        expectsResult = resultContract;
        error = null;
        return true;
    }

    synchronized void launchFailed(String message) {
        if (phase != Phase.INSTALLING || item == null) return;
        phase = Phase.READY;
        error = message;
    }

    synchronized void finish() { if (phase == Phase.INSTALLING) reset(); }

    synchronized Prepared detach(boolean retainedForConfiguration) {
        // Stopped Activities can be destroyed without isFinishing(); only explicit retention owns the state.
        return retainedForConfiguration ? null : reset();
    }

    synchronized Prepared reset() {
        // Atomically transfer cleanup ownership; a worker may complete at the instant of reset.
        Prepared discardable = phase == Phase.INSTALLING ? null : item;
        generation++;
        phase = Phase.IDLE;
        item = null;
        error = null;
        expectsResult = false;
        return discardable;
    }
}
