//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package jp.project2by2.felica;

import android.os.Bundle;

interface IFelica {
    Bundle openSe(String packageName, IBinder token);
    int closeSe(String packageName, int handle, IBinder token);
    Bundle transceiveSe(String packageName, int handle, in byte[] data, int timeoutMs);
    int cancelSe(String packageName, int handle);
    int connectSe(String packageName, int handle);
    int disconnectSe(String packageName, int handle);

    Bundle openRf(String packageName, IBinder token);
    int closeRf(String packageName, int handle, IBinder token);
    Bundle transceiveRf(String packageName, int handle, in byte[] data, int timeoutMs);
    int cancelRf(String packageName, int handle);
    int connectRf(String packageName, int handle, int timeoutMs);
    int disconnectRf(String packageName, int handle);

    boolean enable();
    boolean disable(boolean persist);
    int getState();
    int getRwP2pState();
    boolean setRwP2pMode(boolean enabled);
    void prepareSwitchedOffState();
}
