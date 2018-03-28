package de.cyface.datacapturing;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;

import java.util.concurrent.TimeUnit;

import de.cyface.datacapturing.exception.DataCapturingException;
import de.cyface.datacapturing.exception.SetupException;
import de.cyface.datacapturing.ui.Reason;
import de.cyface.datacapturing.ui.UIListener;
import de.cyface.synchronization.StubAuthenticator;
import de.cyface.synchronization.SynchronisationException;

/**
 * In implementation of the {@link DataCapturingService} as required inside the Movebis project.
 * <p>
 * This implementation provides access to location updates even outside of a running data capturing session. To start
 * these updates use {@link #startUILocationUpdates()}; to stop it use {@link #stopUILocationUpdates()}. It might be
 * necessary to provide a user interface asking the user for location access permissions. You can provide this user
 * interface using {@link UIListener#onRequirePermission(String, Reason)}. This method will be called with
 * <code>ACCESS_COARSE_LOCATION</code> and <code>ACCESS_FINE_LOCATION</code> permission requests.
 * <p>
 * Before you try to measure any data you should provide a valid JWT auth token for data synchronization. You may do
 * this using {@link #registerJWTAuthToken(String, String)} with a token for a certain username. For annonymization it
 * is ok to use some garbage username here. If a user is no longer required, you can deregister it using
 * {@link #deregisterJWTAuthToken(String)}.
 *
 * @author Klemens Muthmann
 * @version 2.1.0
 * @since 2.0.0
 */
public class MovebisDataCapturingService extends DataCapturingService {

    /**
     * The time in milliseconds after which this object stops waiting for the system to pause or resume the Android
     * service and reports an error. It is set to 10 seconds by default. There is no particular reason. We should check
     * what works under real world conditions.
     */
    private final static long PAUSE_RESUME_TIMEOUT_TIME_MILLIS = 10_000L;
    /**
     * A <code>LocationManager</code> that is used to provide location updates for the UI even if no capturing is
     * running.
     */
    private final LocationManager preMeasurementLocationManager;
    /**
     * A listener for location updates, which it passes through to the user interface.
     */
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            uiListener.onLocationUpdate(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Nothing to do here.
        }

        @Override
        public void onProviderEnabled(String provider) {
            // Nothing to do here.
        }

        @Override
        public void onProviderDisabled(String provider) {
            // Nothing to do here.
        }
    };
    /**
     * A listener for events which the UI might be interested in.
     */
    private final UIListener uiListener;
    /**
     * The maximum rate of location updates to receive in seconds. Set this to <code>0L</code>
     * if you would like to be notified as often as possible.
     */
    private final long locationUpdateRate;
    /**
     * A flag set if the locationListener for UI updates is active. This helps us to prevent to register such a listener
     * multiple times.
     */
    private boolean uiUpdatesActive;

    /**
     * Creates a new completely initialized {@link MovebisDataCapturingService}.
     *
     * @param context The context (i.e. <code>Activity</code>) handling this service.
     * @param dataUploadServerAddress The server address running an API that is capable of receiving data captured by
     *            this service.
     * @param uiListener A listener for events which the UI might be interested in.
     * @param locationUpdateRate The maximum rate of location updates to receive in seconds. Set this to <code>0L</code>
     *            if you would like to be notified as often as possible.
     * @throws SetupException If initialization of this service facade fails or writing the components preferences
     *             fails.
     */

    public MovebisDataCapturingService(final @NonNull Context context, final @NonNull String dataUploadServerAddress,
            final @NonNull UIListener uiListener, final long locationUpdateRate) throws SetupException {
        super(context, context.getContentResolver(), dataUploadServerAddress);
        this.locationUpdateRate = locationUpdateRate;
        uiUpdatesActive = false;
        preMeasurementLocationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        if (preMeasurementLocationManager == null) {
            throw new SetupException("Unable to load location manager. Only got null!");
        }
        this.uiListener = uiListener;
    }

    /**
     * Starts the reception of location updates for the user interface. No tracking is started with this method. This is
     * purely intended for display purposes. The received locations are forwared to the {@link UIListener} provided to
     * the constructor.
     */
    @SuppressLint("MissingPermission") // This is ok. We are checking the permission, but lint is too dump to notice.
    public void startUILocationUpdates() {
        if (uiUpdatesActive) {
            return;
        }
        boolean fineLocationAccessIsGranted = checkFineLocationAccess(getContext());
        if (fineLocationAccessIsGranted) {
            preMeasurementLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, locationUpdateRate, 0L,
                    locationListener);
            uiUpdatesActive = true;
        }

        boolean coarseLocationAccessIsGranted = checkCoarseLocationAccess(getContext());
        if (coarseLocationAccessIsGranted) {
            preMeasurementLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, locationUpdateRate,
                    0L, locationListener);
            uiUpdatesActive = true;
        }

    }

    /**
     * Stops reception of location updates for the user interface.
     *
     * @see #startUILocationUpdates()
     */
    public void stopUILocationUpdates() {
        if (!uiUpdatesActive) {
            return;
        }
        preMeasurementLocationManager.removeUpdates(locationListener);
        uiUpdatesActive = false;
    }

    /**
     * Adds a <a href="https://jwt.io/">JWT</a> authentication token for a specific user to Android's account system.
     * After the token has been added it starts periodic data synchronization if not yet active.
     *
     * @param username The username of the user to add an auth token for.
     * @param token The auth token to add.
     * @throws SynchronisationException If unable to create an appropriate account with the Android account system.
     */
    public void registerJWTAuthToken(final @NonNull String username, final @NonNull String token)
            throws SynchronisationException {
        AccountManager accountManager = AccountManager.get(getContext());

        Account synchronizationAccount = getWiFiSurveyor().getOrCreateAccount(username);

        accountManager.setAuthToken(synchronizationAccount, StubAuthenticator.AUTH_TOKEN_TYPE, token);
        getWiFiSurveyor().startSurveillance(synchronizationAccount);
    }

    /**
     * Removes the <a href="https://jwt.io/">JWT</a> auth token for a specific username from the system. If that
     * username was not registered with
     * {@link #registerJWTAuthToken(String, String)} this method simply does nothing.
     *
     * @param username The username of the user to remove the auth token for.
     */
    public void deregisterJWTAuthToken(final @NonNull String username) {
        getWiFiSurveyor().deleteAccount(username);
    }

    /**
     * Pauses the current data capturing, but does not finish the current measurement. This is a synchronized call to an
     * Android service and should be handled as a long running operation.
     * <p>
     * To continue with the measurement just call {@link #resume()}.
     *
     * @throws DataCapturingException If halting the background service was not successful.
     */
    public void pause() throws DataCapturingException {
        stopServiceSync(PAUSE_RESUME_TIMEOUT_TIME_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Resumes the current data capturing after a previous call to {@link #pause()}. This is a synchronized call to an
     * Android service and should be considered a long running operation.
     * <p>
     * You should only call this after an initial call to <code>pause()</code>.
     *
     * @throws DataCapturingException If starting the background service was not successful.
     */
    public void resume() throws DataCapturingException {
        runServiceSync(PAUSE_RESUME_TIMEOUT_TIME_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Checks whether the user has granted the <code>ACCESS_COARSE_LOCATION</code> permission and notifies the UI to ask
     * for it if not.
     * 
     * @param context Current <code>Activity</code> context.
     * @return Either <code>true</code> if permission was or has been granted; <code>false</code> otherwise.
     */
    private boolean checkCoarseLocationAccess(Context context) {
        return ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || uiListener.onRequirePermission(Manifest.permission.ACCESS_COARSE_LOCATION, new Reason(
                        "this app uses information about WiFi and cellular networks to display your position. Please provide your permission to track the networks you are currently using, to see your position on the map."));
    }

    /**
     * Checks whether the user has granted the <code>ACCESS_FINE_LOCATION</code> permission and notifies the UI to ask
     * for it if not.
     *
     * @param context Current <code>Activity</code> context.
     * @return Either <code>true</code> if permission was or has been granted; <code>false</code> otherwise.
     */
    private boolean checkFineLocationAccess(final @NonNull Context context) {
        return ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || uiListener.onRequirePermission(Manifest.permission.ACCESS_FINE_LOCATION, new Reason(
                        "This app uses GPS sensors to display your position. If you would like your position to be shown as exactly as possible please allow access to the GPS sensors."));
    }
}
