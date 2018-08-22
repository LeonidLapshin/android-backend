package de.cyface.synchronization;

import static de.cyface.persistence.AbstractCyfaceMeasurementTable.DATABASE_QUERY_LIMIT;
import static de.cyface.synchronization.TestUtils.AUTHORITY;
import static de.cyface.synchronization.TestUtils.TAG;
import static de.cyface.synchronization.TestUtils.getAccelerationsUri;
import static de.cyface.synchronization.TestUtils.getDirectionsUri;
import static de.cyface.synchronization.TestUtils.getGeoLocationsUri;
import static de.cyface.synchronization.TestUtils.getMeasurementUri;
import static de.cyface.synchronization.TestUtils.getRotationsUri;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.Arrays;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import de.cyface.persistence.GpsPointsTable;
import de.cyface.persistence.MagneticValuePointTable;
import de.cyface.persistence.MeasurementTable;
import de.cyface.persistence.RotationPointTable;
import de.cyface.persistence.SamplePointTable;

/**
 * Tests that instances of the <code>MeasurementContentProviderClient</code> do work correctly.
 *
 * @author Klemens Muthmann
 * @author Armin Schnabel
 * @version 1.0.1
 * @since 2.0.0
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class MeasurementContentProviderClientTest {
    /**
     * Constant you can play around with to find out how many simultaneous operations are possible in one transaction to
     * insert into the <code>ContentProvider</code>.
     */
    private final static int MAX_SIMULTANEOUS_OPERATIONS = 550;

    /**
     * This test makes sure that larger GeoLocation tracks can be loaded completely form the
     * database as there was a bug which limited the query size to 10_000 entries #MOV-248.
     */
    @Test
    public void testLoadGeoLocations_10hTrack() throws RemoteException {
        // The Location frequency is always 1 Hz, i.e. 10h of measurement:
        testLoadGeoLocations(3600 * 10);
    }

    @Ignore
    public void testLoadGeoLocations(int numberOftestEntries) throws RemoteException {
        // Arrange
        Context context = InstrumentationRegistry.getTargetContext();
        ContentProviderClient client = null;
        Cursor locationsCursor = null;
        int numberOfLoadedGeoLocations = 0;

        // Act: Store and load the test entries
        try {
            client = context.getContentResolver().acquireContentProviderClient(AUTHORITY);
            if (client == null) {
                throw new IllegalStateException(String.format(
                        "Unable to initialize content provider client for content provider \"(%s)\"", AUTHORITY));
            }

            ContentValues measurementValues = new ContentValues();
            measurementValues.put(MeasurementTable.COLUMN_VEHICLE, "BICYCLE");
            measurementValues.put(MeasurementTable.COLUMN_FINISHED, 1);
            Uri result = client.insert(getMeasurementUri(), measurementValues);
            if (result == null) {
                throw new IllegalStateException("Measurement insertion failed!");
            }
            final long measurementIdentifier = Long.parseLong(result.getLastPathSegment());

            ContentValues geoLocationValues = new ContentValues();
            geoLocationValues.put(GpsPointsTable.COLUMN_SPEED, 1.0);
            geoLocationValues.put(GpsPointsTable.COLUMN_MEASUREMENT_FK, measurementIdentifier);
            geoLocationValues.put(GpsPointsTable.COLUMN_LON, 1.0);
            geoLocationValues.put(GpsPointsTable.COLUMN_LAT, 1.0);
            geoLocationValues.put(GpsPointsTable.COLUMN_IS_SYNCED, 1.0);
            geoLocationValues.put(GpsPointsTable.COLUMN_GPS_TIME, 1);
            geoLocationValues.put(GpsPointsTable.COLUMN_ACCURACY, 1);
            ContentValues[] geoLocationValuesArray = new ContentValues[numberOftestEntries];
            for (int i = 0; i < numberOftestEntries; i++) {
                geoLocationValuesArray[i] = geoLocationValues;
            }

            long startTime = System.currentTimeMillis();
            // Else we get android.os.TransactionTooLargeException: data parcel size ___ bytes
            for (int startIndex = 0; startIndex < geoLocationValuesArray.length; startIndex += MAX_SIMULTANEOUS_OPERATIONS) {
                int endIndex = Math.min(geoLocationValuesArray.length, startIndex + MAX_SIMULTANEOUS_OPERATIONS);
                // BulkInsert is about 80 times faster than insertBatch
                client.bulkInsert(getGeoLocationsUri(),
                        Arrays.copyOfRange(geoLocationValuesArray, startIndex, endIndex));
                if (startIndex % MAX_SIMULTANEOUS_OPERATIONS * 100 == 0)
                    Log.i(TAG, "Inserting " + startIndex + " entries took: " + (System.currentTimeMillis() - startTime)
                            + " ms");
            }
            Log.i(TAG, "Inserting " + geoLocationValuesArray.length + " entries took: "
                    + (System.currentTimeMillis() - startTime) + " ms");

            // Load entries again
            MeasurementContentProviderClient oocut = new MeasurementContentProviderClient(measurementIdentifier, client,
                    AUTHORITY);
            startTime = System.currentTimeMillis();

            for (int i = 0; i < geoLocationValuesArray.length; i += DATABASE_QUERY_LIMIT) {
                locationsCursor = oocut.loadGeoLocations(i, DATABASE_QUERY_LIMIT);
                while (locationsCursor.moveToNext()) {
                    numberOfLoadedGeoLocations++;
                }
                Log.i(TAG,
                        "Loading " + locationsCursor.getCount() + " entries  took: "
                                + (System.currentTimeMillis() - startTime) + " ms (" + numberOfLoadedGeoLocations + "/"
                                + geoLocationValuesArray.length + ")");
            }
        } finally {
            if (client != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    client.release();
                } else {
                    client.close();
                }
            }
            if (locationsCursor != null) {
                locationsCursor.close();
            }
        }

        // Assert
        assertThat(numberOfLoadedGeoLocations, is(equalTo(numberOftestEntries)));
    }

    @Test
    public void test() throws RemoteException {
        Context context = InstrumentationRegistry.getTargetContext();
        ContentProviderClient client = null;
        Cursor loadedAccelerations = null;
        Cursor loadedRotations = null;
        Cursor loadedDirections = null;
        Cursor loadedEmptyAccelerations = null;
        Cursor loadedEmptyRotations = null;
        Cursor loadedEmptyDirections = null;

        try {
            client = context.getContentResolver().acquireContentProviderClient(AUTHORITY);
            if (client == null) {
                throw new IllegalStateException(String.format(
                        "Unable to initialize content provider client for content provider \"(%s)\"", AUTHORITY));
            }

            ContentValues measurementValues = new ContentValues();
            measurementValues.put(MeasurementTable.COLUMN_VEHICLE, "BICYCLE");
            measurementValues.put(MeasurementTable.COLUMN_FINISHED, 1);
            Uri result = client.insert(getMeasurementUri(), measurementValues);
            if (result == null) {
                throw new IllegalStateException("Measurement insertion failed!");
            }
            long measurementIdentifier = Long.parseLong(result.getLastPathSegment());

            ContentValues geoLocationValues = new ContentValues();
            geoLocationValues.put(GpsPointsTable.COLUMN_SPEED, 1.0);
            geoLocationValues.put(GpsPointsTable.COLUMN_MEASUREMENT_FK, measurementIdentifier);
            geoLocationValues.put(GpsPointsTable.COLUMN_LON, 1.0);
            geoLocationValues.put(GpsPointsTable.COLUMN_LAT, 1.0);
            geoLocationValues.put(GpsPointsTable.COLUMN_IS_SYNCED, 0);
            geoLocationValues.put(GpsPointsTable.COLUMN_GPS_TIME, 1L);
            geoLocationValues.put(GpsPointsTable.COLUMN_ACCURACY, 1);
            client.insert(getGeoLocationsUri(), geoLocationValues);
            client.insert(getGeoLocationsUri(), geoLocationValues);

            ContentValues samplePointValues = new ContentValues();
            samplePointValues.put(SamplePointTable.COLUMN_AX, 1.0);
            samplePointValues.put(SamplePointTable.COLUMN_AY, 1.0);
            samplePointValues.put(SamplePointTable.COLUMN_AZ, 1.0);
            samplePointValues.put(SamplePointTable.COLUMN_TIME, 1L);
            samplePointValues.put(SamplePointTable.COLUMN_IS_SYNCED, 0);
            samplePointValues.put(SamplePointTable.COLUMN_MEASUREMENT_FK, measurementIdentifier);
            client.insert(getAccelerationsUri(), samplePointValues);
            client.insert(getAccelerationsUri(), samplePointValues);
            client.insert(getAccelerationsUri(), samplePointValues);

            ContentValues rotationPointValues = new ContentValues();
            rotationPointValues.put(RotationPointTable.COLUMN_RX, 1.0);
            rotationPointValues.put(RotationPointTable.COLUMN_RY, 1.0);
            rotationPointValues.put(RotationPointTable.COLUMN_RZ, 1.0);
            rotationPointValues.put(RotationPointTable.COLUMN_TIME, 1L);
            rotationPointValues.put(RotationPointTable.COLUMN_IS_SYNCED, 0);
            rotationPointValues.put(RotationPointTable.COLUMN_MEASUREMENT_FK, measurementIdentifier);
            client.insert(getRotationsUri(), rotationPointValues);
            client.insert(getRotationsUri(), rotationPointValues);
            client.insert(getRotationsUri(), rotationPointValues);

            ContentValues directionPointValues = new ContentValues();
            directionPointValues.put(MagneticValuePointTable.COLUMN_MX, 1.0);
            directionPointValues.put(MagneticValuePointTable.COLUMN_MY, 1.0);
            directionPointValues.put(MagneticValuePointTable.COLUMN_MZ, 1.0);
            directionPointValues.put(MagneticValuePointTable.COLUMN_TIME, 1L);
            directionPointValues.put(MagneticValuePointTable.COLUMN_IS_SYNCED, 0);
            directionPointValues.put(MagneticValuePointTable.COLUMN_MEASUREMENT_FK, measurementIdentifier);
            client.insert(getDirectionsUri(), directionPointValues);
            client.insert(getDirectionsUri(), directionPointValues);
            client.insert(getDirectionsUri(), directionPointValues);

            MeasurementContentProviderClient oocut = new MeasurementContentProviderClient(measurementIdentifier, client,
                    AUTHORITY);

            loadedAccelerations = oocut.load3DPoint(MeasurementSerializer.accelerationsSerializer);
            loadedRotations = oocut.load3DPoint(MeasurementSerializer.rotationsSerializer);
            loadedDirections = oocut.load3DPoint(MeasurementSerializer.directionsSerializer);

            assertThat(loadedAccelerations.getCount(), is(equalTo(3)));
            assertThat(loadedRotations.getCount(), is(equalTo(3)));
            assertThat(loadedDirections.getCount(), is(equalTo(3)));

            assertThat(oocut.cleanMeasurement(), is(equalTo(9)));

            loadedEmptyAccelerations = oocut.load3DPoint(MeasurementSerializer.accelerationsSerializer);
            loadedEmptyRotations = oocut.load3DPoint(MeasurementSerializer.rotationsSerializer);
            loadedEmptyDirections = oocut.load3DPoint(MeasurementSerializer.directionsSerializer);

            assertThat(loadedEmptyAccelerations.getCount(), is(equalTo(0)));
            assertThat(loadedEmptyRotations.getCount(), is(equalTo(0)));
            assertThat(loadedEmptyDirections.getCount(), is(equalTo(0)));
        } finally {
            if (client != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    client.release();
                } else {
                    client.close();
                }
            }
            if (loadedAccelerations != null) {
                loadedAccelerations.close();
            }
            if (loadedRotations != null) {
                loadedRotations.close();
            }
            if (loadedDirections != null) {
                loadedDirections.close();
            }
            if (loadedEmptyAccelerations != null) {
                loadedEmptyAccelerations.close();
            }
            if (loadedEmptyRotations != null) {
                loadedEmptyRotations.close();
            }
            if (loadedEmptyDirections != null) {
                loadedEmptyDirections.close();
            }
        }
    }
}