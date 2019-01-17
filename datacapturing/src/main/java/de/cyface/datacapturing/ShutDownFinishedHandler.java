package de.cyface.datacapturing;

import static de.cyface.datacapturing.BundlesExtrasCodes.MEASUREMENT_ID;
import static de.cyface.datacapturing.BundlesExtrasCodes.STOPPED_SUCCESSFULLY;
import static de.cyface.datacapturing.Constants.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

/**
 * Handler for shutdown finished events. Just implement the {@link #shutDownFinished(long)} method with the code you
 * would like to run after the service has been shut down. This class is used for asynchronous calls to
 * <code>DataCapturingService</code> lifecycle methods.
 * <p>
 * To work properly you must register this object as an Android <code>BroadcastReceiver</code>.
 *
 * @author Klemens Muthmann
 * @version 2.0.4
 * @since 2.0.0
 * @see DataCapturingService#pause(ShutDownFinishedHandler)
 * @see DataCapturingService#stop(ShutDownFinishedHandler)
 */
public abstract class ShutDownFinishedHandler extends BroadcastReceiver {

    /**
     * This is set to <code>true</code> if either a <code>MessageCodes.LOCAL_BROADCAST_SERVICE_STOPPED</code> broadcast has
     * been received or a <code>MessageCodes.SERVICE_STOPPED</code> was issued. It is <code>false</code> otherwise.
     */
    private boolean receivedServiceStopped;

    /**
     * Method called if shutdown has been finished.
     *
     * @param measurementIdentifier The identifier of the measurement, that was captured by the stopped capturing
     *            service.
     */
    public abstract void shutDownFinished(final long measurementIdentifier);

    @Override
    public void onReceive(final @NonNull Context context, final @NonNull Intent intent) {
        Log.v(TAG, "Start/Stop Synchronizer received an intent with action " + intent.getAction() + ".");
        if (intent.getAction() == null) {
            throw new IllegalStateException("Received broadcast with null action.");
        }
        switch (intent.getAction()) {
            case MessageCodes.LOCAL_BROADCAST_SERVICE_STOPPED:
                Log.v(TAG, "Received Service stopped broadcast!");
                receivedServiceStopped = true;
                boolean serviceWasStoppedSuccessfully = intent.getBooleanExtra(STOPPED_SUCCESSFULLY, false);
                long measurementIdentifier = -1;
                if (serviceWasStoppedSuccessfully) {
                    measurementIdentifier = intent.getLongExtra(MEASUREMENT_ID, -1);
                    if (measurementIdentifier == -1) {
                        throw new IllegalStateException("No measurement identifier provided for stopped service!");
                    }
                }
                shutDownFinished(measurementIdentifier);
                break;
            default:
                throw new IllegalStateException("Received undefined broadcast " + intent.getAction());
        }

        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(this);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Probably tried to deregister shut down finished broadcast receiver twice.", e);
        }

    }

    /**
     * @return This is set to <code>true</code> if either a <code>MessageCodes.LOCAL_BROADCAST_SERVICE_STOPPED</code>
     *         broadcast has
     *         been received or a <code>MessageCodes.SERVICE_STOPPED</code> was issued. It is <code>false</code>
     *         otherwise.
     */
    public boolean receivedServiceStopped() {
        return receivedServiceStopped;
    }
}
