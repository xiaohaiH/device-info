package com.gathin.deviceinfo;

/** Process-wide gate owner, independent of Activity finish, rotation or multiple instances. */
final class RebootSession {
    private RebootController controller;

    synchronized RebootController attach(RebootController.Access access, boolean restoredBlocked) {
        // A live result is authoritative; a stale Activity snapshot must not overwrite it.
        if (controller == null) controller = new RebootController(access, restoredBlocked);
        return controller;
    }
}
