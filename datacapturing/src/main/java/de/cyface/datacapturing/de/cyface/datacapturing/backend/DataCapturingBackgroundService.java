package de.cyface.datacapturing.de.cyface.datacapturing.backend;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.cyface.datacapturing.MessageCodes;
import de.cyface.datacapturing.de.cyface.datacapturing.model.CapturedData;

/**
 *
 * This is the implementation of the data capturing process running in the background while a Cyface measuring is
 * active. The service is started by a caller and sends messages to that caller, informing it about its status.
 *
 * @author Klemens Muthmann
 * @author Armin Schnabel
 * @version 2.0.0
 * @since 2.0.0
 */
public class DataCapturingBackgroundService extends Service implements CapturingProcessListener {
    private final static String TAG = "de.cyface.datacapturing";
    /**
     * A wake lock used to keep the application active during data capturing.
     */
    private PowerManager.WakeLock wakeLock;
    private final Messenger callerMessenger = new Messenger(new MessageHandler(this));
    private final List<Messenger> clients = new ArrayList<>();
    private SensorManager sensorManager;
    private CapturingProcess dataCapturing;

    @Override
    public IBinder onBind(final Intent intent) {
        Log.d(TAG, String.format("Binding to %s", this.getClass().getName()));
        return callerMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        Log.d(TAG, "Unbinding from data capturing service.");
        return true; // I want to receive calls to onRebind
    }

    @Override
    public void onRebind(final Intent intent) {
        Log.d(TAG, "Rebinding to data capturing service.");
        super.onRebind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Prevent this process from being killed by the system.
        PowerManager powerManager = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "de.cyface.wakelock");
        wakeLock.acquire();

        if (sensorManager == null) {
            sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (dataCapturing != null) {
            dataCapturing.close();
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        Log.d(TAG, "Starting data capturing service.");
        // TODO old service checks if init has been called before? Why? Is this necessary?
        if(intent!=null) { // If this is the initial start command call init.
            init();
        }
        return Service.START_STICKY;
    }

    /**
     * Initializes this service when it is first started. Since the initialising {@code Intent} sometimes comes with
     * onBind and sometimes with
     * onStartCommand and since the {@code Intent} contains the details about the Bluetooth setup,
     * this method makes sure it is only called once and only if the correct {@code Intent} is
     * available.
     */
    private void init() {
        LocationManager locationManager = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);
        GpsStatusHandler gpsStatusHandler = Build.VERSION_CODES.N <= Build.VERSION.SDK_INT
                ? new GnssStatusCallback(locationManager)
                : new GpsStatusListener(locationManager);
        dataCapturing = new GPSCapturingProcess(locationManager, sensorManager, gpsStatusHandler);

        dataCapturing.addCapturingProcessListener(this);
    }

    /**
     * This method sends an inter process communication (IPC) message to all callers of this service.
     *
     * @param messageCode A code identifying the message that is send. See {@link MessageCodes} for further details.
     * @param data The data to send appended to this message. This may be <code>null</code> if no data needs to be send.
     */
    private void informCaller(final int messageCode, final Parcelable data) {
        Message msg = Message.obtain(null, messageCode);

        if (data != null) {
            Bundle dataBundle = new Bundle();
            dataBundle.putParcelable("data", data);
            msg.setData(dataBundle);
        }

        for (Messenger caller : clients) {
            try {
                caller.send(msg);
            } catch (RemoteException e) {
                Log.w(TAG,
                        String.format("Unable to send message (%s) to caller %s due to exception: %s", msg, caller, e));
            }
        }
    }

    @Override
    public void onPointCaptured(final CapturedData data) {
        informCaller(MessageCodes.POINT_CAPTURED, data);
    }

    @Override
    public void onGpsFix() {
        informCaller(MessageCodes.GPS_FIX, null);
    }

    @Override
    public void onGpsFixLost() {
        informCaller(MessageCodes.NO_GPS_FIX, null);
    }

    /**
     * <p>
     * Handles clients which are sending (private!) inter process messages to this service (e.g. the UI thread).
     * - The Handler code runs all on the same (e.g. UI) thread.
     * - We don't use Broadcasts here to reduce the amount of broadcasts.
     * </p>
     *
     * @author Klemens Muthmann
     * @version 1.0.0
     * @since 1.0.0
     */
    private final static class MessageHandler extends Handler {
        /**
         * <p>
         * A weak reference to the {@link DataCapturingBackgroundService} responsible for this message
         * handler. The weak reference is necessary to avoid memory leaks if the handler outlives
         * the service.
         * </p>
         * <p>
         * For reference see for example
         * <a href="http://www.androiddesignpatterns.com/2013/01/inner-class-handler-memory-leak.html">here</a>.
         * </p>
         */
        private final WeakReference<DataCapturingBackgroundService> context;

        /**
         * <p>
         * Creates a new completely initialized {@link MessageHandler} for messages to this
         * service.
         * </p>
         *
         * @param context The {@link DataCapturingBackgroundService} receiving messages via this handler.
         */
        MessageHandler(final DataCapturingBackgroundService context) {
            if (context == null) {
                throw new IllegalArgumentException("Illegal argument: context was null!");
            }

            this.context = new WeakReference<>(context);
        }

        @Override
        public void handleMessage(final Message msg) {
            if (msg == null) {
                throw new IllegalArgumentException("Illegal argument: msg was null!");
            }

            DataCapturingBackgroundService service = context.get();

            switch (msg.what) {
                case MessageCodes.REGISTER_CLIENT:
                    if (service.clients.contains(msg.replyTo)) {
                        throw new IllegalStateException("Client " + msg.replyTo + " already registered.");
                    }
                    service.clients.add(msg.replyTo);
                    break;
                case MessageCodes.UNREGISTER_CLIENT:
                    if (!service.clients.contains(msg.replyTo)) {
                        throw new IllegalStateException("Tried to unregister not registered client " + msg.replyTo);
                    }
                    service.clients.remove(msg.replyTo);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }
}
