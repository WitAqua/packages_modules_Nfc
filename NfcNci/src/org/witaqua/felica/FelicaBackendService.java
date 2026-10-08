//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package org.witaqua.felica;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.nfc.NfcService;

import java.util.HashMap;
import java.util.Map;

// Session model, as in the stock LG NfcService (com.android.nfc.FelicaService):
//
//   open        claims the element for the caller and pins RF discovery to
//               listen-only (NfcService.setFelicaSessionActiveAndWait).
//               No NCI traffic towards the element yet.
//   connect     opens the wired (NFCEE) connection.
//   transceive  only while connected.
//   disconnect  closes the wired connection.  The element answers external
//               readers again.
//   close       releases the claim and unpins discovery.
//
// Mobile FeliCa Client relies on connect/disconnect being real: its reset
// path (FelicaSeController.reset with disconnect), the TCAP device used for
// online issuance (TcapFelicaDevice.execute/executeThru/close) and every
// select() go through disconnect -> connect, and a client-side "connected"
// flag that the backend does not share ends up with transceive failing on a
// connection that is gone.
public final class FelicaBackendService extends IFelica.Stub {
    private static final String TAG = "FelicaBackendService";
    private static final String SERVICE_NAME = "org.witaqua.felica.IFelica/default";

    // Same vocabulary as com.felicanetworks.felica (ChipController.TYPE_NFC_*).
    private static final int ERROR_NONE = 0;
    private static final int ERROR_FAILED = -99;
    private static final int ERROR_INVALID_PARAM = -10;
    private static final int ERROR_BUSY = -11;
    private static final int ERROR_INVALID_STATUS = -11;
    private static final int ERROR_TIMEOUT = -13;
    private static final int ERROR_NOT_AVAILABLE = -18;

    private static final int STATE_OFF = 1;
    private static final int STATE_ON = 3;

    private static final int NO_DEVICE_HANDLE = -1;

    private static final long ROUTING_TIMEOUT_MS = 1500;
    private static final int WARMUP_TRANSCEIVE_TIMEOUT_MS = 1000;
    private static final byte[] WARMUP_POLLING_COMMAND =
            new byte[] {0x06, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00};

    private final NativeFelicaSe mNativeSe = new NativeFelicaSe();
    private final Map<Integer, Session> mSeSessions = new HashMap<>();
    private final Map<Integer, Session> mRfSessions = new HashMap<>();
    private int mNextSeHandle = 1;
    private int mNextRfHandle = 1;

    // Serializes opening and closing the wired connection.  Not held across a
    // session's transceive, so that cancel can still reach the native side.
    private final Object mNativeLock = new Object();

    // The stock service holds a partial wake lock across every connect,
    // transceive and disconnect (mFelicaServiceWakeLock).  An online issuance
    // runs long enough for the screen to go off in the middle of it.
    private final PowerManager.WakeLock mWakeLock;

    private FelicaBackendService(Context context) {
        PowerManager pm = context != null ? context.getSystemService(PowerManager.class) : null;
        mWakeLock = pm != null
                ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FelicaBackendService")
                : null;
    }

    public static void register(Context context) {
        try {
            if (checkService(SERVICE_NAME) != null) {
                Log.i(TAG, SERVICE_NAME + " already registered");
                return;
            }

            addService(SERVICE_NAME, new FelicaBackendService(context));
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

    private void acquireWakeLock() {
        if (mWakeLock != null) {
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    // Pins discovery while any SE or RF session is open, unpins after the last
    // one is gone.  Returns the error to hand back to the caller.
    private int pinDiscovery() {
        int ret = NfcService.setFelicaSessionActiveAndWait(true, ROUTING_TIMEOUT_MS);
        if (ret != ERROR_NONE) {
            Log.w(TAG, "pinning discovery failed error=" + ret);
        }
        return ret;
    }

    private void unpinDiscoveryIfIdle() {
        synchronized (this) {
            if (!mSeSessions.isEmpty() || !mRfSessions.isEmpty()) {
                return;
            }
        }
        NfcService.setFelicaSessionActiveAndWait(false, ROUTING_TIMEOUT_MS);
    }

    private synchronized Session getSeSession(int handle) {
        return mSeSessions.get(handle);
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

        int ret = pinDiscovery();
        if (ret != ERROR_NONE) {
            unpinDiscoveryIfIdle();
            return openError(ret);
        }

        Session session;
        synchronized (this) {
            if (!mSeSessions.isEmpty()) {
                session = null;
            } else {
                session = new Session(mNextSeHandle++, token, true);
                if (mNextSeHandle <= 0) {
                    mNextSeHandle = 1;
                }
                mSeSessions.put(session.handle, session);
            }
        }
        if (session == null) {
            return openError(ERROR_BUSY);
        }

        try {
            token.linkToDeath(session, 0);
        } catch (RemoteException e) {
            synchronized (this) {
                mSeSessions.remove(session.handle);
            }
            unpinDiscoveryIfIdle();
            return openError(ERROR_FAILED);
        }

        return openSuccess(session.handle);
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
        // The stock service refuses to close a connected session.  Mobile
        // FeliCa always disconnects first anyway; doing it here keeps a
        // client that does not from leaving the wired connection behind.
        disconnectNative(session);
        unpinDiscoveryIfIdle();
        return ERROR_NONE;
    }

    @Override
    public Bundle transceiveSe(String packageName, int handle, byte[] data, int timeoutMs) {
        if (data == null || data.length == 0 || timeoutMs < 0) {
            return transceiveError(ERROR_INVALID_PARAM);
        }

        Session session = getSeSession(handle);
        if (session == null) {
            return transceiveError(ERROR_INVALID_PARAM);
        }
        int deviceHandle = session.deviceHandle;
        if (deviceHandle == NO_DEVICE_HANDLE) {
            // Same answer as the stock service for a session that was opened
            // but not connected.
            return transceiveError(ERROR_INVALID_STATUS);
        }

        int[] error = new int[] {ERROR_FAILED};
        byte[] response;
        acquireWakeLock();
        try {
            response = mNativeSe.transceive(deviceHandle, data, timeoutMs, error);
        } finally {
            releaseWakeLock();
        }

        Bundle b = new Bundle();
        b.putByteArray("out", response);
        b.putInt("e", error[0]);
        return b;
    }

    @Override
    public int cancelSe(String packageName, int handle) {
        Session session = getSeSession(handle);
        if (session == null) {
            return ERROR_INVALID_PARAM;
        }
        int deviceHandle = session.deviceHandle;
        if (deviceHandle == NO_DEVICE_HANDLE) {
            return ERROR_INVALID_STATUS;
        }

        mNativeSe.cancel(deviceHandle);
        return ERROR_NONE;
    }

    @Override
    public int connectSe(String packageName, int handle) {
        Session session = getSeSession(handle);
        if (session == null) {
            return ERROR_INVALID_PARAM;
        }

        synchronized (mNativeLock) {
            if (session.deviceHandle != NO_DEVICE_HANDLE) {
                return ERROR_NONE;
            }

            int deviceHandle;
            acquireWakeLock();
            try {
                deviceHandle = mNativeSe.open();
            } finally {
                releaseWakeLock();
            }
            if (deviceHandle < 0) {
                Log.w(TAG, "connectSe: NativeFelicaSe.open failed error=" + deviceHandle);
                return deviceHandle;
            }

            if (getSeSession(handle) != session) {
                // Closed (or its client died) while we were connecting.
                mNativeSe.close(deviceHandle);
                return ERROR_INVALID_PARAM;
            }
            session.deviceHandle = deviceHandle;
            Log.i(TAG, "connectSe handle=" + handle + " device=" + deviceHandle);
            return ERROR_NONE;
        }
    }

    @Override
    public int disconnectSe(String packageName, int handle) {
        Session session = getSeSession(handle);
        if (session == null) {
            return ERROR_INVALID_PARAM;
        }
        return disconnectNative(session);
    }

    private int disconnectNative(Session session) {
        synchronized (mNativeLock) {
            int deviceHandle = session.deviceHandle;
            if (deviceHandle == NO_DEVICE_HANDLE) {
                return ERROR_NONE;
            }
            session.deviceHandle = NO_DEVICE_HANDLE;

            int ret;
            acquireWakeLock();
            try {
                ret = mNativeSe.close(deviceHandle);
            } finally {
                releaseWakeLock();
            }
            Log.i(TAG, "disconnectSe handle=" + session.handle + " result=" + ret);
            return ret;
        }
    }

    @Override
    public Bundle openRf(String packageName, IBinder token) {
        Log.i(TAG, "openRf package=" + packageName);

        if (token == null) {
            return openError(ERROR_INVALID_PARAM);
        }

        synchronized (this) {
            if (!mRfSessions.isEmpty()) {
                for (Session session : mRfSessions.values()) {
                    if (session.token == token) {
                        return openSuccess(session.handle);
                    }
                }
                return openError(ERROR_BUSY);
            }
        }

        // The stock service pins discovery for an RF session too
        // (FELICA_STATE_RF in VNfcService.applyRoutingForFn).
        int ret = pinDiscovery();
        if (ret != ERROR_NONE) {
            unpinDiscoveryIfIdle();
            return openError(ret);
        }

        Session session;
        synchronized (this) {
            if (!mRfSessions.isEmpty()) {
                session = null;
            } else {
                session = new Session(mNextRfHandle++, token, false);
                if (mNextRfHandle <= 0) {
                    mNextRfHandle = 1;
                }
                mRfSessions.put(session.handle, session);
            }
        }
        if (session == null) {
            return openError(ERROR_BUSY);
        }

        try {
            token.linkToDeath(session, 0);
        } catch (RemoteException e) {
            synchronized (this) {
                mRfSessions.remove(session.handle);
            }
            unpinDiscoveryIfIdle();
            return openError(ERROR_FAILED);
        }

        return openSuccess(session.handle);
    }

    @Override
    public int closeRf(String packageName, int handle, IBinder token) {
        Session session;

        synchronized (this) {
            session = mRfSessions.get(handle);
            if (session == null || (token != null && session.token != token)) {
                return ERROR_INVALID_PARAM;
            }
            mRfSessions.remove(handle);
        }

        session.token.unlinkToDeath(session, 0);
        unpinDiscoveryIfIdle();
        return ERROR_NONE;
    }

    // There is no reader/writer path to an external card yet.  Answer the way
    // the stock service does when nothing answers the polling: connect times
    // out (-13, "no card"), and transceive on a session that never got
    // connected is an invalid state (-11).  Not -18: Mobile FeliCa reads that
    // as "FeliCa is locked".
    @Override
    public Bundle transceiveRf(String packageName, int handle, byte[] data, int timeoutMs) {
        synchronized (this) {
            if (!mRfSessions.containsKey(handle)) {
                return transceiveError(ERROR_INVALID_PARAM);
            }
        }
        return transceiveError(ERROR_INVALID_STATUS);
    }

    @Override
    public int cancelRf(String packageName, int handle) {
        synchronized (this) {
            return mRfSessions.containsKey(handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
        }
    }

    @Override
    public int connectRf(String packageName, int handle, int timeoutMs) {
        synchronized (this) {
            return mRfSessions.containsKey(handle) ? ERROR_TIMEOUT : ERROR_INVALID_PARAM;
        }
    }

    @Override
    public int disconnectRf(String packageName, int handle) {
        synchronized (this) {
            return mRfSessions.containsKey(handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
        }
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

        synchronized (mNativeLock) {
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
        final boolean se;
        // Native NFCEE handle while connected (SE only).  Guarded by mNativeLock
        // for writes; read without it by transceive/cancel.
        volatile int deviceHandle = NO_DEVICE_HANDLE;

        Session(int handle, IBinder token, boolean se) {
            this.handle = handle;
            this.token = token;
            this.se = se;
        }

        @Override
        public void binderDied() {
            synchronized (FelicaBackendService.this) {
                if (se) {
                    mSeSessions.remove(handle);
                } else {
                    mRfSessions.remove(handle);
                }
            }

            if (se) {
                try {
                    disconnectNative(this);
                } catch (RuntimeException e) {
                    Log.w(TAG, "disconnect after binderDied failed", e);
                }
            }
            unpinDiscoveryIfIdle();
        }
    }
}
