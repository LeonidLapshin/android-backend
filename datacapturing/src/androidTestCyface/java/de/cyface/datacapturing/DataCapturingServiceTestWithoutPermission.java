package de.cyface.datacapturing;

import static de.cyface.datacapturing.ServiceTestUtils.ACCOUNT_TYPE;
import static de.cyface.datacapturing.ServiceTestUtils.AUTHORITY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import de.cyface.datacapturing.exception.DataCapturingException;
import de.cyface.datacapturing.exception.MissingPermissionException;
import de.cyface.datacapturing.exception.SetupException;
import de.cyface.datacapturing.model.CapturedData;
import de.cyface.datacapturing.model.GeoLocation;
import de.cyface.datacapturing.model.Vehicle;
import de.cyface.datacapturing.ui.Reason;
import de.cyface.datacapturing.ui.UIListener;

/**
 * Checks if missing permissions are correctly detected before starting a service.
 *
 * @author Klemens Muthmann
 * @version 1.0.3
 * @since 2.0.0
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
@FlakyTest
@Ignore // Ignore this test until Android is capable of resetting permissions for every test
public class DataCapturingServiceTestWithoutPermission {

    /**
     * An object of the class under test.
     */
    private DataCapturingService oocut;

    /**
     * Initializes the object of class under test.
     */
    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver contentResolver = context.getContentResolver();
        final String dataUploadServerAddress = "https://localhost:8080";
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    oocut = new CyfaceDataCapturingService(context, contentResolver, AUTHORITY, ACCOUNT_TYPE,
                            dataUploadServerAddress, new IgnoreEventsStrategy());
                } catch (SetupException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    /**
     * Tests that the service correctly throws an <code>Exception</code> if no <code>ACCESS_FINE_LOCATION</code> was
     * granted.
     *
     * @throws MissingPermissionException The expected <code>Exception</code> thrown if the
     *             <code>ACCESS_FINE_LOCATION</code> is missing.
     * @throws DataCapturingException If the asynchronous background service did not start successfully.
     */
    @Test(expected = MissingPermissionException.class)
    public void testServiceDoesNotStartWithoutPermission() throws MissingPermissionException, DataCapturingException {
        oocut.startSync(new NonSynchronizedTestListener(), Vehicle.UNKNOWN);
    }

    /**
     * Tests whether a set {@link UIListener} is correctly informed about a missing permission.
     */
    @Test
    public void testUIListenerIsInformedOfMissingPermission() {
        TestUIListener uiListener = new TestUIListener();
        oocut.setUiListener(uiListener);

        boolean exceptionCaught = false;
        try {
            oocut.startSync(new NonSynchronizedTestListener(), Vehicle.UNKNOWN);
        } catch (DataCapturingException | MissingPermissionException e) {
            assertThat(uiListener.requiredPermission, is(equalTo(true)));
            exceptionCaught = true;
        }
        assertThat(exceptionCaught, is(equalTo(true)));
    }

    /**
     * A <code>DataCapturingListener</code> that can be used for testing, which is not synchronized with the test.
     *
     * @author Klemens Muthmann
     * @version 1.0.1
     * @since 2.0.0
     */
    private static class NonSynchronizedTestListener implements DataCapturingListener {

        @Override
        public void onFixAcquired() {

        }

        @Override
        public void onFixLost() {

        }

        @Override
        public void onNewGeoLocationAcquired(GeoLocation position) {

        }

        @Override
        public void onNewSensorDataAcquired(CapturedData data) {

        }

        @Override
        public void onLowDiskSpace(DiskConsumption allocation) {

        }

        @Override
        public void onSynchronizationSuccessful() {

        }

        @Override
        public void onErrorState(Exception e) {

        }

        @Override
        public boolean onRequiresPermission(String permission, Reason reason) {
            return false;
        }

        @Override
        public void onCapturingStopped() {

        }
    }
}
