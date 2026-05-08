//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package jp.project2by2.felica;

import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.nfc.NfcService;

import java.util.HashMap;
import java.util.Map;

public final class FelicaBackendService extends IFelica.Stub {
    private static final String TAG = "FelicaBackendService";
    private static final String SERVICE_NAME = "jp.project2by2.felica.IFelica/default";

    private static final int ERROR_NONE = 0;
    private static final int ERROR_FAILED = -99;
    private static final int ERROR_INVALID_PARAM = -10;
    private static final int ERROR_BUSY = -11;
    private static final int ERROR_NOT_AVAILABLE = -18;

    private static final int STATE_OFF = 1;
    private static final int STATE_ON = 3;

    private static final long ROUTING_TIMEOUT_MS = 1500;
    private static final int WARMUP_TRANSCEIVE_TIMEOUT_MS = 1000;
    private static final byte[] WARMUP_POLLING_COMMAND =
            new byte[] {0x06, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00};

    private final NativeFelicaSe mNativeSe = new NativeFelicaSe();
    private final Map<Integer, Session> mSeSessions = new HashMap<>();

    private FelicaBackendService() {
    }

    public static void register() {
        try {
            if (checkService(SERVICE_NAME) != null) {
                Log.i(TAG, SERVICE_NAME + " already registered");
                return;
            }

            addService(SERVICE_NAME, new FelicaBackendService());
            Log.i(TAG, "registered " + SERVICE_NAME);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "failed to register " + SERVICE_NAME, e);
        }
    }

    private static IBinder checkService(String name) throws ReflectiveOperationException {
        return (IBinder) ServiceManager.class.getMethod("checkService", String.class)
                .invoke(null, name);
    }

    private static void addService(String name, IBinder service) throws ReflectiveOperationException {
        ServiceManager.class.getMethod("addService", String.class, IBinder.class)
                .invoke(null, name, service);
    }

    @Override
    public Bundle openSe(String packageName, IBinder token) {
        Log.i(TAG, "openSe package=" + packageName);

        if (token == null) {
            return openError(ERROR_INVALID_PARAM);
        }

        synchronized (this) {
            if (!mSeSessions.isEmpty()) {
                for (Session session : mSeSessions.values()) {
                    if (session.token == token) {
                        return openSuccess(session.handle);
                    }
                }
                return openError(ERROR_BUSY);
            }
        }

        if (!NfcService.requestFelicaRoutingAndWait(ROUTING_TIMEOUT_MS)) {
            Log.w(TAG, "openSe routing failed");
            return openError(ERROR_FAILED);
        }

        int handle = mNativeSe.open();
        if (handle < 0) {
            Log.w(TAG, "NativeFelicaSe.open failed error=" + handle);
            return openError(handle);
        }

        Session session = new Session(handle, token);
        try {
            token.linkToDeath(session, 0);
        } catch (RemoteException e) {
            mNativeSe.close(handle);
            return openError(ERROR_FAILED);
        }

        synchronized (this) {
            if (!mSeSessions.isEmpty()) {
                token.unlinkToDeath(session, 0);
                mNativeSe.close(handle);
                return openError(ERROR_BUSY);
            }
            mSeSessions.put(handle, session);
        }

        return openSuccess(handle);
    }

    @Override
    public int closeSe(String packageName, int handle, IBinder token) {
        Session session;

        synchronized (this) {
            session = mSeSessions.get(handle);
            if (session == null || (token != null && session.token != token)) {
                return ERROR_INVALID_PARAM;
            }
            mSeSessions.remove(handle);
        }

        session.token.unlinkToDeath(session, 0);
        return mNativeSe.close(handle);
    }

    @Override
    public Bundle transceiveSe(String packageName, int handle, byte[] data, int timeoutMs) {
        if (data == null || data.length == 0) {
            return transceiveError(ERROR_INVALID_PARAM);
        }

        synchronized (this) {
            if (!mSeSessions.containsKey(handle)) {
                return transceiveError(ERROR_INVALID_PARAM);
            }
        }

        int[] error = new int[] {ERROR_FAILED};
        byte[] response = mNativeSe.transceive(handle, data, timeoutMs, error);

        Bundle b = new Bundle();
        b.putByteArray("out", response);
        b.putInt("e", error[0]);
        return b;
    }

    @Override
    public int cancelSe(String packageName, int handle) {
        synchronized (this) {
            if (!mSeSessions.containsKey(handle)) {
                return ERROR_INVALID_PARAM;
            }
        }

        mNativeSe.cancel(handle);
        return ERROR_NONE;
    }

    @Override
    public int connectSe(String packageName, int handle) {
        synchronized (this) {
            return mSeSessions.containsKey(handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
        }
    }

    @Override
    public int disconnectSe(String packageName, int handle) {
        synchronized (this) {
            return mSeSessions.containsKey(handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
        }
    }

    @Override
    public Bundle openRf(String packageName, IBinder token) {
        return openError(ERROR_NOT_AVAILABLE);
    }

    @Override
    public int closeRf(String packageName, int handle, IBinder token) {
        return ERROR_NOT_AVAILABLE;
    }

    @Override
    public Bundle transceiveRf(String packageName, int handle, byte[] data, int timeoutMs) {
        return transceiveError(ERROR_NOT_AVAILABLE);
    }

    @Override
    public int cancelRf(String packageName, int handle) {
        return ERROR_NOT_AVAILABLE;
    }

    @Override
    public int connectRf(String packageName, int handle, int timeoutMs) {
        return ERROR_NOT_AVAILABLE;
    }

    @Override
    public int disconnectRf(String packageName, int handle) {
        return ERROR_NOT_AVAILABLE;
    }

    @Override
    public boolean enable() {
        return warmupSe();
    }

    @Override
    public boolean disable(boolean persist) {
        return true;
    }

    @Override
    public int getState() {
        return NfcService.isFelicaNfcEnabled() ? STATE_ON : STATE_OFF;
    }

    @Override
    public int getRwP2pState() {
        return STATE_ON;
    }

    @Override
    public boolean setRwP2pMode(boolean enabled) {
        return enabled ? warmupSe() : true;
    }

    @Override
    public void prepareSwitchedOffState() {
    }

    private boolean warmupSe() {
        synchronized (this) {
            if (!mSeSessions.isEmpty()) {
                Log.i(TAG, "warmupSe skipped: SE session already open");
                return true;
            }
        }

        if (!NfcService.requestFelicaRoutingAndWait(ROUTING_TIMEOUT_MS)) {
            Log.w(TAG, "warmupSe routing failed");
            return false;
        }

        int handle = mNativeSe.open();
        if (handle < 0) {
            Log.w(TAG, "warmupSe open failed error=" + handle);
            return false;
        }

        int[] error = new int[] {ERROR_FAILED};
        byte[] response = mNativeSe.transceive(
                handle, WARMUP_POLLING_COMMAND, WARMUP_TRANSCEIVE_TIMEOUT_MS, error);

        int closeResult = mNativeSe.close(handle);

        Log.i(TAG, "warmupSe completed handle=" + handle
                + " responseLen=" + (response != null ? response.length : -1)
                + " error=" + error[0]
                + " closeResult=" + closeResult);

        return error[0] == ERROR_NONE;
    }

    private static Bundle openSuccess(int handle) {
        Bundle b = new Bundle();
        b.putInt("out", handle);
        b.putInt("e", ERROR_NONE);
        return b;
    }

    private static Bundle openError(int error) {
        Bundle b = new Bundle();
        b.putInt("out", -1);
        b.putInt("e", error);
        return b;
    }

    private static Bundle transceiveError(int error) {
        Bundle b = new Bundle();
        b.putByteArray("out", null);
        b.putInt("e", error);
        return b;
    }

    private final class Session implements IBinder.DeathRecipient {
        final int handle;
        final IBinder token;

        Session(int handle, IBinder token) {
            this.handle = handle;
            this.token = token;
        }

        @Override
        public void binderDied() {
            synchronized (FelicaBackendService.this) {
                mSeSessions.remove(handle);
            }

            try {
                mNativeSe.close(handle);
            } catch (RuntimeException e) {
                Log.w(TAG, "close after binderDied failed", e);
            }
        }
    }
}
