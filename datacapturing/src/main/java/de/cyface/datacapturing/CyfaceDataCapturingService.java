package de.cyface.datacapturing;

import android.accounts.Account;
import android.content.Context;
import android.support.annotation.NonNull;

import de.cyface.datacapturing.exception.SetupException;
import de.cyface.synchronization.SynchronisationException;

/**
 * An implementation of a <code>DataCapturingService</code> using a dummy Cyface account for data synchronization.
 *
 * @author Klemens Muthmann
 * @version 1.0.1
 * @since 2.0.0
 */
public final class CyfaceDataCapturingService extends DataCapturingService {
    /**
     * Default dummy account used for data synchronization.
     */
    private final static String ACCOUNT = "default_account";

    /**
     * Creates a new completely initialized {@link DataCapturingService}.
     *
     * @param context The context (i.e. <code>Activity</code>) handling this service.
     * @param dataUploadServerAddress The server address running an API that is capable of receiving data captured by
     *            this service.
     * @throws SetupException If writing the components preferences or registering the dummy user account fails.
     */
    public CyfaceDataCapturingService(@NonNull Context context, @NonNull String dataUploadServerAddress)
            throws SetupException {
        super(context, dataUploadServerAddress);
        try {
            Account account = getWiFiSurveyor().getOrCreateAccount(ACCOUNT);
            getWiFiSurveyor().startSurveillance(account);
        } catch (SynchronisationException e) {
            throw new SetupException(e);
        }
    }
}
