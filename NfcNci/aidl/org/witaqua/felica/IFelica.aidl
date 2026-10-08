//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package org.witaqua.felica;

import android.os.Bundle;

/**
 * The NFC-side half of the "felica" service. FelicaService republishes
 * this as com.felicanetworks.felica.IFelicaAdapter and passes results
 * through untouched, so the values here are that API's own:
 *
 * - int results and the "e" entry of a returned Bundle are its error
 *   codes: 0 ok, -10 invalid parameter, -11 busy or not connected,
 *   -12 a tag is in the field, -13 timeout, -17 canceled,
 *   -18 not available, -99 anything else.
 * - A Bundle carries the handle (open*) or the response bytes
 *   (transceive*) under "out".
 * - getState() and getRwP2pState() answer 1 (off) or 3 (on); the
 *   frontend maps the latter onto FelicaAdapterExtra's 11/13.
 */
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
