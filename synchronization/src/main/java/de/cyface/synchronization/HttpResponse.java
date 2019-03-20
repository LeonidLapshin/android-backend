/*
 * Copyright 2017 Cyface GmbH
 * This file is part of the Cyface SDK for Android.
 * The Cyface SDK for Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * The Cyface SDK for Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with the Cyface SDK for Android. If not, see <http://www.gnu.org/licenses/>.
 */
package de.cyface.synchronization;

import java.net.HttpURLConnection;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.NonNull;

/**
 * Internal value object class for the attributes of an HTTP response. It wrappers the HTTP
 * status code as well as a JSON body object.
 *
 * @author Armin Schnabel
 * @author Klemens Muthmann
 * @version 2.0.0
 * @since 1.0.0
 */
class HttpResponse {

    private int responseCode;
    private JSONObject body;

    /**
     * Checks the responseCode and responseBody before constructing the {@link HttpResponse}.
     *
     * @param responseCode the HTTP status code returned by the server
     * @param responseBody the HTTP response body returned by the server. Can be null when the login
     *            was successful and there was nothing to return (defined by the Spring API).
     * @throws ResponseParsingException when the server returned something not parsable.
     * @throws BadRequestException When server returns {@code HttpURLConnection#HTTP_BAD_REQUEST}
     * @throws UnauthorizedException When the server returns {@code HttpURLConnection#HTTP_UNAUTHORIZED}
     */
    HttpResponse(final int responseCode, @NonNull final String responseBody)
            throws ResponseParsingException, BadRequestException, UnauthorizedException {
        this.responseCode = responseCode;
        try {
            this.body = new JSONObject(responseBody);
        } catch (final JSONException e) {
            if (is2xxSuccessful()) {
                this.body = null; // Nothing to complain, the login was successful
                return;
            }
            if (responseCode == HttpURLConnection.HTTP_BAD_REQUEST) {
                throw new BadRequestException(String.format("HttpResponse constructor failed: '%s'.", e.getMessage()),
                        e);
            }
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                // Occurred in the RadVerS project
                throw new UnauthorizedException(String
                        .format("401 Unauthorized Error: '%s'. Unable to read the http response.", e.getMessage()), e);
            }
            throw new ResponseParsingException(
                    String.format("HttpResponse constructor failed: '%s'. Response (code %s) body: %s", e.getMessage(),
                            responseCode, responseBody),
                    e);
        }
    }

    @NonNull
    JSONObject getBody() {
        return body;
    }

    int getResponseCode() {
        return responseCode;
    }

    /**
     * Checks if the HTTP response code says "successful".
     *
     * @return true if the code is a 200er code
     */
    boolean is2xxSuccessful() {
        return (responseCode >= 200 && responseCode < 300);
    }
}
