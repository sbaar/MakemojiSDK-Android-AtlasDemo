package com.layer.messenger.flavor;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import com.layer.atlas.provider.Participant;
import com.layer.atlas.provider.ParticipantProvider;
import com.layer.messenger.AuthenticationProvider;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AppParticipantProvider implements ParticipantProvider {
    private final static String TAG = AppParticipantProvider.class.getSimpleName();

    // Placeholder for avatar URLs

    private enum URLS {
        UNKNOWN(null, null),
        RON("ron@layer.com", "https://layer.com/old/images/about/people/Ron_Palmeri@2x.jpg"),
        TOMAZ("tomaz@layer.com", "https://layer.com/old/images/about/people/Tomaz_Stolfa@2x.jpg"),
        STEVIE("stevie@layer.com", "https://layer.com/old/images/about/people/Stevie_Case@2x.jpg"),
        NIL("nil@layer.com", "https://layer.com/old/images/about/people/Nil_Gradisnik@2x.jpg"),
        DEAN("dean@layer.com", "https://layer.com/old/images/about/people/Dean_Talanehzar@2x.jpg"),
        KEVIN("kevin@layer.com", "https://layer.com/old/images/about/people/Kevin_Coleman@2x.jpg"),
        MICHAEL("michael@layer.com", "https://layer.com/old/images/about/people/Michael_Kantor@2x.jpg"),
        DOUG("doug@layer.com", "https://layer.com/old/images/about/people/Doug_Rapp@2x.jpg"),
        ALEX("alex@layer.com", "https://layer.com/old/images/about/people/Alex_von_Oech@2x.jpg"),
        ABIR("abir@layer.com", "https://layer.com/old/images/about/people/Abir_Majumdar@2x.jpg"),
        BLAKE("blake@layer.com", "https://layer.com/old/images/about/people/Blake_Watters@2x.jpg"),
        HEATHER("heather@layer.com", "https://layer.com/old/images/about/people/Heather_Blackmore@2x.jpg"),
        STEVEN("steven@layer.com", "https://layer.com/old/images/about/people/Steven_Jones@2x.jpg"),
        KLEMEN("klemen@layer.com", "https://layer.com/old/images/about/people/Klemen_Verdnik@2x.jpg"),
        AMAR("amar@layer.com", "https://layer.com/old/images/about/people/Amar_Srinivasan@2x.jpg"),
        VIVEK("vivek@layer.com", "https://layer.com/old/images/about/people/Vivek_Trehan@2x.jpg");

        private final String mEmail;
        private final String mUrl;

        URLS(String email, String url) {
            mEmail = email;
            mUrl = url;
        }

        public String getEmail() {
            return mEmail;
        }

        public String getUrl() {
            return mUrl;
        }

        public static URLS fromEmail(String email) {
            if (email == null) return null;
            URLS match = UNKNOWN;
            for (URLS url : values()) {
                if (email.equals(url.getEmail())) return url;
            }
            return match;
        }
    }

    private final Context mContext;
    private final Queue<ParticipantListener> mParticipantListeners = new ConcurrentLinkedQueue<>();
    private final Map<String, AppParticipant> mParticipantMap = new HashMap<String, AppParticipant>();
    private final AtomicBoolean mFetching = new AtomicBoolean(false);

    private AppAuthenticationProvider mAuthenticationProvider;

    public AppParticipantProvider(Context context) {
        mContext = context.getApplicationContext();
        load();
        fetchParticipants();
    }

    public AppParticipantProvider setLayerAppId(Uri layerAppId) {
        return this;
    }

    public AppParticipantProvider setAuthenticationProvider(AuthenticationProvider authenticationProvider) {
        mAuthenticationProvider = (AppAuthenticationProvider) authenticationProvider;
        fetchParticipants();
        return this;
    }

    //==============================================================================================
    // Atlas ParticipantProvider
    //==============================================================================================

    @Override
    public Map<String, Participant> getMatchingParticipants(String filter, Map<String, Participant> result) {
        if (result == null) {
            result = new HashMap<String, Participant>();
        }

        synchronized (mParticipantMap) {
            // With no filter, return all Participants
            if (filter == null) {
                result.putAll(mParticipantMap);
                return result;
            }

            // Filter participants by substring matching first- and last- names
            filter = filter.toLowerCase();
            for (AppParticipant p : mParticipantMap.values()) {
                boolean matches = false;
                if (p.getName() != null && p.getName().toLowerCase().contains(filter))
                    matches = true;
                if (matches) {
                    result.put(p.getId(), p);
                } else {
                    result.remove(p.getId());
                }
            }
            return result;
        }
    }

    @Override
    public Participant getParticipant(String userId) {
        synchronized (mParticipantMap) {
            AppParticipant participant = mParticipantMap.get(userId);
            if (participant != null) return participant;
            fetchParticipants();
            return null;
        }
    }

    /**
     * Adds the provided Participants to this ParticipantProvider, saves the participants, and
     * returns the list of added participant IDs.
     */
    private AppParticipantProvider setParticipants(Collection<AppParticipant> participants) {
        List<String> newParticipantIds = new ArrayList<>(participants.size());
        synchronized (mParticipantMap) {
            for (AppParticipant participant : participants) {
                String participantId = participant.getId();
                if (!mParticipantMap.containsKey(participantId)) {
                    newParticipantIds.add(participantId);
                }
                mParticipantMap.put(participantId, participant);
            }
            save();
        }
        alertParticipantsUpdated(newParticipantIds);
        return this;
    }


    //==============================================================================================
    // Persistence
    //==============================================================================================

    /**
     * Loads additional participants from SharedPreferences
     */
    private boolean load() {
        synchronized (mParticipantMap) {
            String jsonString = mContext.getSharedPreferences("participants", Context.MODE_PRIVATE).getString("json", null);
            if (jsonString == null) return false;

            try {
                for (AppParticipant participant : participantsFromJson(new JSONArray(jsonString))) {
                    mParticipantMap.put(participant.getId(), participant);
                }
                return true;
            } catch (JSONException e) {
                Log.e(TAG, e.getMessage(), e);
            }
            return false;
        }
    }

    /**
     * Saves the current map of participants to SharedPreferences
     */
    private boolean save() {
        synchronized (mParticipantMap) {
            try {
                mContext.getSharedPreferences("participants", Context.MODE_PRIVATE).edit()
                        .putString("json", participantsToJson(mParticipantMap.values()).toString())
                        .commit();
                return true;
            } catch (JSONException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        return false;
    }


    //==============================================================================================
    // Network operations
    //==============================================================================================
    private AppParticipantProvider fetchParticipants() {
        if (mAuthenticationProvider == null) return this;
        AppAuthenticationProvider.Credentials credentials = mAuthenticationProvider.getCredentials();
        if (credentials == null) return this;
        if (credentials.getAuthToken() == null) return this;

        if (!mFetching.compareAndSet(false, true)) return this;
        new AsyncTask<AppAuthenticationProvider.Credentials, Void, Void>() {
            protected Void doInBackground(AppAuthenticationProvider.Credentials... params) {
                try {
                    // Post request
                    AppAuthenticationProvider.Credentials credentials = params[0];
                    String url = "http://layer-identity-provider.herokuapp.com/users.json";
                    HttpGet get = new HttpGet(url);
                    get.setHeader("Content-Type", "application/json");
                    get.setHeader("Accept", "application/json");
                    get.setHeader("X_LAYER_APP_ID", credentials.getLayerAppId());
                    if (credentials.getEmail() != null) {
                        get.setHeader("X_AUTH_EMAIL", credentials.getEmail());
                    }
                    if (credentials.getAuthToken() != null) {
                        get.setHeader("X_AUTH_TOKEN", credentials.getAuthToken());
                    }
                    HttpResponse response = new DefaultHttpClient().execute(get);

                    // Handle failure
                    int statusCode = response.getStatusLine().getStatusCode();
                    if (statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_CREATED) {
                        Log.e(TAG, String.format("Got status %d when fetching participants", statusCode));
                        return null;
                    }

                    // Parse response
                    JSONArray json = new JSONArray(EntityUtils.toString(response.getEntity()));
                    setParticipants(participantsFromJson(json));
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage(), e);
                } finally {
                    mFetching.set(false);
                }
                return null;
            }
        }.execute(credentials);
        return this;
    }


    //==============================================================================================
    // Utils
    //==============================================================================================

    private static List<AppParticipant> participantsFromJson(JSONArray participantArray) throws JSONException {
        List<AppParticipant> participants = new ArrayList<>(participantArray.length());
        for (int i = 0; i < participantArray.length(); i++) {
            JSONObject participantObject = participantArray.getJSONObject(i);
            AppParticipant participant = new AppParticipant();
            participant.setId(participantObject.optString("id", null));
            participant.setFirstName(trimmedValue(participantObject, "first_name", null));
            participant.setLastName(trimmedValue(participantObject, "last_name", null));
            participant.setEmail(trimmedValue(participantObject, "email", null));
            String url = URLS.fromEmail(participant.getEmail()).getUrl();
            if (url != null) participant.setAvatarUrl(Uri.parse(url));
            participants.add(participant);
        }
        return participants;
    }

    private static String trimmedValue(JSONObject o, String name, String fallback) {
        String s = o.optString(name, fallback);
        return (s == null) ? null : s.trim();
    }

    private static JSONArray participantsToJson(Collection<AppParticipant> participants) throws JSONException {
        JSONArray participantsArray = new JSONArray();
        for (AppParticipant participant : participants) {
            JSONObject participantObject = new JSONObject();
            participantObject.put("id", participant.getId());
            participantObject.put("first_name", participant.getFirstName());
            participantObject.put("last_name", participant.getLastName());
            participantObject.put("email", participant.getEmail());
            participantsArray.put(participantObject);
        }
        return participantsArray;
    }

    private AppParticipantProvider registerParticipantListener(ParticipantListener participantListener) {
        if (!mParticipantListeners.contains(participantListener)) {
            mParticipantListeners.add(participantListener);
        }
        return this;
    }

    private AppParticipantProvider unregisterParticipantListener(ParticipantListener participantListener) {
        mParticipantListeners.remove(participantListener);
        return this;
    }

    private void alertParticipantsUpdated(Collection<String> updatedParticipantIds) {
        for (ParticipantListener listener : mParticipantListeners) {
            listener.onParticipantsUpdated(this, updatedParticipantIds);
        }
    }


    //==============================================================================================
    // Callbacks
    //==============================================================================================

    public interface ParticipantListener {
        void onParticipantsUpdated(AppParticipantProvider provider, Collection<String> updatedParticipantIds);
    }
}