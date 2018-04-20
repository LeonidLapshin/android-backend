package de.cyface.datacapturing.backend;

import static de.cyface.datacapturing.MessageCodes.ACTION_PING;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.support.annotation.NonNull;
import android.util.Log;

import de.cyface.datacapturing.BundlesExtrasCodes;
import de.cyface.datacapturing.MessageCodes;
import de.cyface.datacapturing.model.CapturedData;
import de.cyface.datacapturing.model.GeoLocation;
import de.cyface.datacapturing.model.Point3D;
import de.cyface.datacapturing.persistence.MeasurementPersistence;
import de.cyface.datacapturing.ui.CapturingNotification;

/**
 *
 * This is the implementation of the data capturing process running in the background while a Cyface measuring is
 * active. The service is started by a caller and sends messages to that caller, informing it about its status.
 *
 * @author Klemens Muthmann
 * @author Armin Schnabel
 * @version 3.0.2
 * @since 2.0.0
 */
public class DataCapturingBackgroundService extends Service implements CapturingProcessListener {

    /*
     * MARK: Properties
     */

    /**
     * The tag used to identify logging messages send to logcat.
     */
    private final static String TAG = "de.cyface.datacapturing";
    /**
     * The maximum size of captured data transmitted to clients of this service in one call. If there are more captured points they are split into multiple messages.
     */
    private final static int MAXIMUM_CAPTURED_DATA_MESSAGE_SIZE = 400;
    /**
     * A wake lock used to keep the application active during data capturing.
     */
    private PowerManager.WakeLock wakeLock;
    /**
     * The Android <code>Messenger</code> used to send IPC messages, informing the caller about the current status of
     * data capturing.
     */
    private final Messenger callerMessenger = new Messenger(new MessageHandler(this));
    /**
     * The list of clients receiving messages from this service as well as sending controll messages.
     */
    private final Set<Messenger> clients = new HashSet<>();
    /**
     * A <code>CapturingProcess</code> implementation which is responsible for actual data capturing.
     */
    private CapturingProcess dataCapturing;
    /**
     * A facade handling reading and writing data from and to the Android content provider used to store and retrieve
     * measurement data.
     */
    private MeasurementPersistence persistenceLayer;
    /**
     * Receiver for pings to the service. The receiver answers with a pong as long as this service is running.
     */
    private PingReceiver pingReceiver = new PingReceiver();

    /**
     * The identifier of the measurement to save all the captured data to.
     */
    private long currentMeasurementIdentifier;

    /*
     * MARK: Service Lifecycle Methods
     */

    @Override
    public IBinder onBind(final @NonNull Intent intent) {
        Log.d(TAG, String.format("Binding to %s", this.getClass().getName()));
        return callerMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(final @NonNull Intent intent) {
        Log.d(TAG, "Unbinding from data capturing service.");
        return true; // I want to receive calls to onRebind
    }

    @Override
    public void onRebind(final @NonNull Intent intent) {
        Log.d(TAG, "Rebinding to data capturing service.");
        super.onRebind(intent);
    }

    @SuppressLint("WakelockTimeout") // We can not provide a timeout since our service might need to run for hours.
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        persistenceLayer = new MeasurementPersistence(this.getContentResolver());

        // Prevent this process from being killed by the system.
        PowerManager powerManager = (PowerManager)getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "de.cyface.wakelock");
            wakeLock.acquire();
        } else {
            Log.w(TAG, "Unable to acquire PowerManager. No wake lock set!");
        }
        Log.v(TAG, "Registering Ping Receiver");
        registerReceiver(pingReceiver, new IntentFilter(ACTION_PING));

        Log.d(TAG, "finishedOnCreate");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        Log.v(TAG, "Unregistering Ping receiver.");
        unregisterReceiver(pingReceiver);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (dataCapturing != null) {
            dataCapturing.close();
        }
        // Since on some devices the broadcast seems not to work we are sending a message here.
        //informCaller(MessageCodes.SERVICE_STOPPED,null);
        super.onDestroy();
        Log.v(TAG, "Sending broadcast service stopped.");
        sendBroadcast(new Intent(MessageCodes.BROADCAST_SERVICE_STOPPED));

    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        Log.d(TAG, "Starting data capturing service.");
        // TODO old service checks if init has been called before? Why? Is this necessary?
        if (intent != null) { // If this is the initial start command call init.
            long measurementIdentifier = intent.getLongExtra(BundlesExtrasCodes.START_WITH_MEASUREMENT_ID, -1);
            if (measurementIdentifier==-1) {
                throw new IllegalStateException("No valid measurement identifier for started service provided.");
            }
            this.currentMeasurementIdentifier = measurementIdentifier;
            init();
        }
        Log.v(TAG, "Sending broadcast service started.");
        sendBroadcast(new Intent(MessageCodes.BROADCAST_SERVICE_STARTED));
        return Service.START_STICKY;
    }

    /*
     * MARK: Methods
     */

    /**
     * Initializes this service when it is first started. Since the initialising {@code Intent} sometimes comes with
     * onBind and sometimes with
     * onStartCommand and since the {@code Intent} contains the details about the Bluetooth setup,
     * this method makes sure it is only called once and only if the correct {@code Intent} is
     * available.
     */
    private void init() {
        /* Notification shown to the user while the data capturing is active. */
        CapturingNotification capturingNotification = new CapturingNotification();
        startForeground(capturingNotification.getNotificationId(), capturingNotification.getNotification(this));
        LocationManager locationManager = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);
        GeoLocationDeviceStatusHandler gpsStatusHandler = Build.VERSION_CODES.N <= Build.VERSION.SDK_INT
                ? new GnssStatusCallback(locationManager)
                : new GPSStatusListener(locationManager);
        SensorManager sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        dataCapturing = new GPSCapturingProcess(locationManager, sensorManager, gpsStatusHandler);

        dataCapturing.addCapturingProcessListener(this);

    }

    /**
     * This method sends an inter process communication (IPC) message to all callers of this service.
     *
     * @param messageCode A code identifying the message that is send. See {@link MessageCodes} for further details.
     * @param data The data to send appended to this message. This may be <code>null</code> if no data needs to be send.
     */
    void informCaller(final int messageCode, final Parcelable data) {
        Message msg = Message.obtain(null, messageCode);

        if (data != null) {
            Bundle dataBundle = new Bundle();
            dataBundle.putParcelable("data", data);
            msg.setData(dataBundle);
        }

        Log.v(TAG,String.format("Sending message %d to %d callers.",messageCode,clients.size()));
        Set<Messenger> iterClients = new HashSet<>(clients);
        for (Messenger caller : iterClients) {
            try {
                caller.send(msg);
            } catch (RemoteException e) {
                Log.w(TAG, String.format("Unable to send message (%s) to caller %s!", msg, caller), e);
                clients.remove(caller);

            } catch (NullPointerException e) {
                // Calle may be null in a typical React Native application.
                Log.w(TAG, String.format("Unable to send message (%s) to null caller!", msg), e);
                clients.remove(caller);
            }
        }
    }

    /*
     * MARK: CapturingProcessListener Interface
     */

    @Override
    public void onDataCaptured(final @NonNull CapturedData data) {
        List<Point3D> accelerations = data.getAccelerations();
        List<Point3D> rotations = data.getRotations();
        List<Point3D> directions = data.getDirections();
        int iterationSize = Math.max(accelerations.size(),Math.max(directions.size(),rotations.size()));
        for(int i=0;i<iterationSize;i+=MAXIMUM_CAPTURED_DATA_MESSAGE_SIZE) {
            int endIndex = i+MAXIMUM_CAPTURED_DATA_MESSAGE_SIZE;
            int toAccelerationsIndex = Math.min(endIndex, accelerations.size());
            int toRotationsIndex = Math.min(endIndex, rotations.size());
            int toDirectionsIndex = Math.min(endIndex, directions.size());
            CapturedData dataSublist = new CapturedData(accelerations.subList(i, toAccelerationsIndex), rotations.subList(i, toRotationsIndex), rotations.subList(i, toDirectionsIndex));
            informCaller(MessageCodes.DATA_CAPTURED, dataSublist);
            persistenceLayer.storeData(dataSublist, currentMeasurementIdentifier);
        }
    }

    @Override
    public void onLocationCaptured(final @NonNull GeoLocation location) {
        informCaller(MessageCodes.LOCATION_CAPTURED, location);
        persistenceLayer.storeLocation(location, currentMeasurementIdentifier);
    }

    @Override
    public void onLocationFix() {
        informCaller(MessageCodes.GPS_FIX, null);
    }

    @Override
    public void onLocationFixLost() {
        informCaller(MessageCodes.NO_GPS_FIX, null);
    }

    /**
     * Handles clients which are sending (private!) inter process messages to this service (e.g. the UI thread).
     * - The Handler code runs all on the same (e.g. UI) thread.
     * - We don't use Broadcasts here to reduce the amount of broadcasts.
     *
     * @author Klemens Muthmann
     * @version 1.0.0
     * @since 1.0.0
     */
    private final static class MessageHandler extends Handler {
        /**
         * A weak reference to the {@link DataCapturingBackgroundService} responsible for this message
         * handler. The weak reference is necessary to avoid memory leaks if the handler outlives
         * the service.
         * <p>
         * For reference see for example
         * <a href="http://www.androiddesignpatterns.com/2013/01/inner-class-handler-memory-leak.html">here</a>.
         */
        private final WeakReference<DataCapturingBackgroundService> context;

        /**
         * Creates a new completely initialized {@link MessageHandler} for messages to this
         * service.
         *
         * @param context The {@link DataCapturingBackgroundService} receiving messages via this handler.
         */
        MessageHandler(final @NonNull DataCapturingBackgroundService context) {
            this.context = new WeakReference<>(context);
        }

        @Override
        public void handleMessage(final @NonNull Message msg) {
            Log.d(TAG, String.format("Service received message %s", msg.what));

            DataCapturingBackgroundService service = context.get();

            switch (msg.what) {
                case MessageCodes.REGISTER_CLIENT:
                    Log.d(TAG, "Registering client!");
                    if (service.clients.contains(msg.replyTo)) {
                        Log.w(TAG, "Client " + msg.replyTo + " already registered.");
                    }
                    service.clients.add(msg.replyTo);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }
}
