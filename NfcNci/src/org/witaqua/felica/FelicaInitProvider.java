//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package org.witaqua.felica;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

public final class FelicaInitProvider extends ContentProvider {
    private static final String TAG = "FelicaInitProvider";

    // The only client of the backend.  Osaifu-Keitai is a japanese-market
    // feature, so most devices do not ship it.
    private static final String FELICA_SERVICE_PACKAGE = "org.witaqua.felica";

    @Override
    public boolean onCreate() {
        if (!isFelicaServiceInstalled()) {
            Log.i(TAG, FELICA_SERVICE_PACKAGE + " is not installed, not registering IFelica");
            return true;
        }

        Log.i(TAG, "registering IFelica/default");
        FelicaBackendService.register();
        return true;
    }

    private boolean isFelicaServiceInstalled() {
        Context context = getContext();
        if (context == null) {
            return false;
        }

        try {
            ApplicationInfo info =
                    context.getPackageManager().getApplicationInfo(FELICA_SERVICE_PACKAGE, 0);
            // Only the preinstalled one.  The backend is privileged.
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }
}
