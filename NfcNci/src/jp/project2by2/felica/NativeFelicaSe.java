//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package jp.project2by2.felica;

/** Reverse engineered native felica SE endpoint */
public final class NativeFelicaSe {
    private native void doCancel(int handle);

    private native int doClose(int handle);

    private native int doOpen();

    private native byte[] doTransceive(int handle, byte[] command, int timeoutMs, int[] error);

    public void cancel(int handle) {
        doCancel(handle);
    }

    public int close(int handle) {
        return doClose(handle);
    }

    public int open() {
        return doOpen();
    }

    public byte[] transceive(int handle, byte[] command, int timeoutMs, int[] error) {
        return doTransceive(handle, command, timeoutMs, error);
    }
}
