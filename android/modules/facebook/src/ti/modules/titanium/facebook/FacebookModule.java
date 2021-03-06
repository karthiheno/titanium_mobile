/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2010 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package ti.modules.titanium.facebook;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollInvocation;
import org.appcelerator.kroll.KrollModule;
import org.appcelerator.kroll.annotations.Kroll;
import org.appcelerator.titanium.TiContext;
import org.appcelerator.titanium.kroll.KrollCallback;
import org.appcelerator.titanium.util.Log;
import org.appcelerator.titanium.util.TiActivityResultHandler;
import org.appcelerator.titanium.util.TiActivitySupport;
import org.appcelerator.titanium.util.TiConfig;
import org.appcelerator.titanium.util.TiConvert;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;

import ti.modules.titanium.facebook.FBRequest.FBRequestDelegate;
import ti.modules.titanium.facebook.FBSession.FBSessionDelegate;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Window;

@Kroll.module
public class FacebookModule extends KrollModule implements FBActivityDelegate,
		TiActivityResultHandler {
	private static final String LCAT = "TiFacebook";
	private static final boolean DBG = TiConfig.LOGD;

	@Kroll.constant public static final String LOGIN_BUTTON_STYLE_WIDE = "wide";
	@Kroll.constant public static final String LOGIN_BUTTON_STYLE_NORMAL = "normal";
	
	private FBSession session;
	private ProgressDialog progressDialog;
	private final Handler handler;

	private KrollCallback setupCallback;
	private KrollCallback loginCallback;
	private KrollCallback logoutCallback;

	private Map<Integer, KrollCallback> callbacks = new HashMap<Integer, KrollCallback>(
			1);
	
	public static final String NETWORK_USER_AGENT = System.getProperties().getProperty("http.agent");
	public static String USER_AGENT;

	public FacebookModule(TiContext tiContext) {
		super(tiContext);

		FBActivity.registerActivity("login_dialog", this);
		FBActivity.registerActivity("permission_dialog", this);
		FBActivity.registerActivity("feed_dialog", this);
		FBActivity.registerActivity("stream_dialog", this);

		handler = new Handler(this);

		if (USER_AGENT==null)
		{
			USER_AGENT = NETWORK_USER_AGENT + " Titanium/" + tiContext.getTiApp().getTiBuildVersion();
		}
	}

	FBSession getOrCreateSession(String key, String secret, String sessionProxy) {
		if (session != null) {
			if (key.equals(session.getApiKey())) {
				return session;
			}
		}

		this.session = null;

		if (sessionProxy != null) {
			this.session = FBSession.getSessionForApplication_getSessionProxy(
					key, sessionProxy, new FBSessionDelegateImpl());
		} else {
			this.session = FBSession.getSessionForApplication_secret(key,
					secret, new FBSessionDelegateImpl());
		}

		return session;
	}

	@Kroll.method
	public boolean setup(String key, String secret, String sessionProxy,
			KrollCallback callback) 
	{
		Log.d(LCAT, "setup called with key: " + key + ", secret: "
						+ secret + ", sessionProxy: " + sessionProxy
						+ ", callback: " + callback);

		this.setupCallback = callback;
		if (sessionProxy != null) {
			this.session = FBSession.getSessionForApplication_getSessionProxy(
					key, sessionProxy, new FBSessionDelegateImpl());
		} else {
			this.session = FBSession.getSessionForApplication_secret(key,
					secret, new FBSessionDelegateImpl());
		}

		Window w = getTiContext().getRootActivity().getWindow();
		boolean loggedIn = session.resume(w.getContext());

		Log.d(LCAT, "setup returned " + loggedIn + " from resume");

		if (loggedIn) {
			triggerLoginChange();
		}

		return loggedIn;
	}

	@Kroll.getProperty @Kroll.method
	public boolean isLoggedIn() {
		if (session != null) {
			return session.isConnected();
		}
		return false;
	}

	@Kroll.getProperty @Kroll.method
	public long getUserId() {
		if (session != null && session.isConnected()) {
			return session.getUid();
		}
		return 0L;
	}

	@Kroll.method
	public void query(String fql, KrollCallback callback) {
		Map<String, String> params = Collections.singletonMap("query", fql);
		FBRequest.requestWithDelegate(new FBQueryRequestDelegateImpl(callback))
				.call("facebook.fql.query", params);
	}

	@Kroll.method
	public void execute(String method, KrollDict params,
			KrollCallback callback, Object dataObj) {
		
		HashMap<String, String> fbParams = new HashMap<String, String>();
		for (String key : params.keySet()) {
			fbParams.put(key, TiConvert.toString(params.get(key)));
		}
		
		FBRequest.requestWithDelegate(new FBQueryRequestDelegateImpl(callback))
				.callWithAnyData(method, fbParams, dataObj);
	}

	@Kroll.method
	public void login(KrollCallback callback) {
		this.loginCallback = callback;
		if (!isLoggedIn()) {
			executeLogin();
		}
	}
	
	public void executeLogin() {
		
		Log.d(LCAT, "EXECUTE LOGIN CALLED");
		
		Activity activity = getTiContext().getActivity();
		TiActivitySupport activitySupport = (TiActivitySupport) activity;
		final int resultCode = activitySupport.getUniqueResultCode();

		Intent intent = new Intent(activity, FBActivity.class);
		intent.setAction("login_dialog");
		intent.putExtra("uid", resultCode);
		activitySupport.launchActivityForResult(intent, resultCode,
				(TiActivityResultHandler) this);
	}

	@Kroll.method
	public void logout(KrollCallback callback) {
		this.logoutCallback = callback;
		if (isLoggedIn()) {
			executeLogout();
		}
	}
	
	public void executeLogout() {
		Log.d(LCAT, "EXECUTE LOGOUT CALLED");
		final Window w = getTiContext().getRootActivity().getWindow();
		session.logout(w.getContext());
	}

	@Kroll.method
	public boolean hasPermission(String permission) {
		if (session != null) {
			return session.hasPermission(permission);
		}
		return false;
	}

	@Kroll.method
	public void requestPermission(String permission, KrollCallback callback) {
		Log.d(LCAT, "request permission called for permission: " + permission);
		if (hasPermission(permission)) {
			Log.d(LCAT, "found cached permission: " + permission);
			KrollDict event = new KrollDict();
			event.put("success", true);
			event.put("permission", permission);
			callback.call(event);
		} else {
			Log.d(LCAT, "making remote permission call for: " + permission);
			Activity activity = getTiContext().getActivity();
			TiActivitySupport activitySupport = (TiActivitySupport) activity;
			final int resultCode = activitySupport.getUniqueResultCode();
			Intent intent = new Intent(activity, FBActivity.class);
			callbacks.put(resultCode, callback);
			intent.setAction("permission_dialog");
			intent.putExtra("permission", permission);
			intent.putExtra("uid", resultCode);
			activitySupport.launchActivityForResult(intent, resultCode,
					(TiActivityResultHandler) this);
		}
	}

	@Kroll.method
	public void publishStream(KrollInvocation invocation, String title, KrollDict data, String target,
			KrollCallback callback) {
		JSONObject json = TiConvert.toJSON(data);
		//Activity activity = getTiContext().getActivity();
		Activity activity = invocation.getTiContext().getActivity();
		TiActivitySupport activitySupport = (TiActivitySupport) activity;
		final int resultCode = activitySupport.getUniqueResultCode();
		Intent intent = new Intent(activity, FBActivity.class);
		intent.setAction("stream_dialog");
		callbacks.put(resultCode, callback);
		intent.putExtra("userMessagePrompt", title);
		intent.putExtra("targetId", target);
		if (json!=null)
		{
			intent.putExtra("attachment", json.toString());
		}
		// intent.putExtra("actionLinks",actionLinks);
		intent.putExtra("uid", resultCode);
		activitySupport.launchActivityForResult(intent, resultCode,
				(TiActivityResultHandler) this);
	}

	@Kroll.method
	public void publishFeed(KrollInvocation invocation, long templateBundleId, String data, String body,
			KrollCallback callback) {
		//Activity activity = getTiContext().getActivity();
		Activity activity = invocation.getTiContext().getActivity();
		TiActivitySupport activitySupport = (TiActivitySupport) activity;
		final int resultCode = activitySupport.getUniqueResultCode();
		Intent intent = new Intent(activity, FBActivity.class);
		intent.setAction("feed_dialog");
		callbacks.put(resultCode, callback);
		intent.putExtra("templateId", templateBundleId);
		intent.putExtra("templateData", data);
		intent.putExtra("bodyGeneral", body);
		// intent.putExtra("userMessagePrompt",userMessagePrompt);
		intent.putExtra("uid", resultCode);
		activitySupport.launchActivityForResult(intent, resultCode,
				(TiActivityResultHandler) this);
	}

	public void onError(Activity activity, int requestCode, Exception e) {
		Log.e(LCAT, "onError = " + requestCode, e);
	}

	public void onResult(Activity activity, int requestCode, int resultCode,
			Intent data) {
		Log.d(LCAT, "onResult = " + requestCode + ", resultCode=" + resultCode
				+ ", data = " + data);
		if (callbacks.containsKey(requestCode)) {
			KrollCallback callback = callbacks.remove(requestCode);
			if (DBG)
				Log.d(LCAT, "onResult callback = " + callback);
			if (callback != null) {
				KrollDict event = new KrollDict();
				event.put("success", resultCode == Activity.RESULT_OK);
				event.put("cancel", resultCode == Activity.RESULT_CANCELED);
				if (data != null) {
					String permission = data.getStringExtra("permission");
					if (permission != null) {
						event.put("permission", permission);
					}
				}
				callback.call(event);
				Log.d(LCAT, "Calling post activity event = " + event + " to "
						+ callback);
			}
		}
	}

	// -------------------------------------------------------------------------------------------------------------//
	// -------------------------------------------------------------------------------------------------------------//

	public void triggerLogIn() {
		KrollDict event = new KrollDict();
		event.put("success", true);
		event.put("state", "login");
		event.put("uid", getUserId());
		triggerLogIn(event);
	}

	private void triggerLogIn(KrollDict event) {
		Log.d(LCAT, "++trigger login");
		
		if (session.hasUnsetPermissions())
		{
			triggerLoginChange(false);
		}
		
		setProperty("loggedIn", isLoggedIn());
		setProperty("userId", getUserId());

		KrollDict sessionDict = new KrollDict();
		if (isLoggedIn()) {
			sessionDict.put("user", getUserId());
			sessionDict.put("session_key", session.getSessionKey());
			sessionDict.put("ss", session.getSessionSecret());
			sessionDict.put("expires", session.getExpirationDate());
		}
		setProperty("session", sessionDict);

		if (loginCallback != null) {
			loginCallback.call(event);
			loginCallback = null; // one-shot
		}
		if (setupCallback != null)
			setupCallback.call(event);
		
		fireEvent("login",sessionDict);
	}

	public void triggerLogOut() {

		Log.d(LCAT, "++trigger logout");
		
		KrollDict event = new KrollDict();
		event.put("success", true);
		event.put("state", "logout");

		setProperty("loggedIn", false);
		setProperty("userId", 0);
		setProperty("session", new KrollDict());

		if (logoutCallback != null) {
			logoutCallback.call(event);
			logoutCallback = null; // one-shot
		}
		if (setupCallback != null)
			setupCallback.call(event);
		
		fireEvent("logout",event);
	}

	public FBDialog onCreate(String action, Activity activity, Bundle state) {
		Intent data = activity.getIntent();
		FBDialog dialog = null;

		if (action.equals("permission_dialog")) {
			String permission = data.getStringExtra("permission");
			dialog = new FBPermissionDialog(activity, session, new String[]{permission});
		} else if (action.equals("login_dialog")) {
			dialog = new FBLoginDialog(activity, session);
		} else if (action.equals("feed_dialog")) {
			Long templateId = data.getLongExtra("templateId", 0L);
			String templateData = data.getStringExtra("templateData");
			String bodyGeneral = data.getStringExtra("bodyGeneral");
			String userMessagePrompt = data.getStringExtra("userMessagePrompt");
			dialog = new FBFeedDialog(activity, session, templateId,
					templateData, bodyGeneral, userMessagePrompt);
		} else if (action.equals("stream_dialog")) {
			String attachment = data.getStringExtra("attachment");
			String actionLinks = data.getStringExtra("actionLinks");
			String targetId = data.getStringExtra("targetId");
			String userMessagePrompt = data.getStringExtra("userMessagePrompt");
			dialog = new FBStreamDialog(activity, session, this, attachment,
					actionLinks, targetId, userMessagePrompt);
		}
		if (dialog != null) {
			activity.setContentView(dialog);
			return dialog;
		} else {
			Log.e(LCAT, "Error finding action: " + action);
			return null;
		}
	}

	public void triggerLoginChange() {
		triggerLoginChange(true);
	}

	// this is a special method called by the dialog when a login is successful
	// or when our session is first loaded
	// to get the user information and their permissions so we can cache them
	public void triggerLoginChange(boolean showDialog) {
		Log.d(LCAT, "triggerLoginChange called with UID = " + session.getUid());

		if (showDialog) {
			handler.post(new Runnable() {
				public void run() {
					Window w = getTiContext().getRootActivity().getWindow();
					progressDialog = new ProgressDialog(w.getContext());
					progressDialog.setMessage("Loading...One moment");
					progressDialog.setIndeterminate(true);
					progressDialog.setCancelable(false);
					progressDialog.show();
				}
			});
		}

		String fql = "select uid,name from user where uid == "
				+ session.getUid();
		String fql2 = "select status_update,photo_upload,sms,email,create_event,rsvp_event,publish_stream,read_stream,share_item,create_note from permissions where uid == "
				+ session.getUid();

		String json = null;
		try {
			json = new JSONStringer().object().key("session").value(fql).key(
					"permissions").value(fql2).endObject().toString();
		} catch (JSONException e) {
			e.printStackTrace();
		}

		Map<String, String> params = Collections.singletonMap("queries", json);
		FBRequest.requestWithDelegate(new FBLoginRequestDelegateImpl()).call(
				"facebook.fql.multiquery", params);
	}

	private final class FBSessionDelegateImpl extends FBSessionDelegate {
		@Override
	    public void sessionDidLogin(FBSession session, Long uid){
			Log.i(LCAT, "++ Facebook session login for " + uid);
			triggerLogIn();
			if (session.hasUnsetPermissions()) {
				triggerLoginChange(false);
			}
		}

		@Override
		public void sessionDidLogout(FBSession session) {
			triggerLogOut();
		}
	}

	private final class FBLoginRequestDelegateImpl extends FBRequestDelegate {
		@SuppressWarnings("unchecked")
		@Override
        public void requestDidLoad(FBRequest request, String contentType, Object result) {
			Log.d(LCAT, "FBLoginRequest finished with result=" + result);

			if (result instanceof JSONArray) {
				JSONArray jsonArray = (JSONArray) result;
				try {
					for (int c = 0; c < jsonArray.length(); c++) {
						JSONObject jo = jsonArray.getJSONObject(c);
						String item = jo.getString("name");
						JSONObject items = jo.getJSONArray("fql_result_set")
								.getJSONObject(0);
						if (item.equals("permissions")) {
							// set the permissions on the session
							Map<String, String> perms = new HashMap<String, String>();
							Iterator iter = items.keys();
							while (iter.hasNext()) {
								String key = (String) iter.next();
								String value = items.getString(key);
								perms.put(key, value);
							}
							setProperty("permissions", perms);
							Window w = getTiContext().getRootActivity().getWindow();
							session.setPermissions(w.getContext(),perms);
							Log.d(LCAT, "PERMISSIONS = " + perms);
						}
					}
				} catch (JSONException e) {
					Log.e(LCAT, "Error loading Facebook JSON response", e);
				}
			} else {
				Log.w(LCAT, "FB Login response was not JSON. Result was "
						+ result);
			}

			if (progressDialog != null) {
				progressDialog.dismiss();
				progressDialog = null;
			}
		}

		@Override
        public void requestDidFailWithError(FBRequest request, Throwable error) {
			Log.e(LCAT, "FBLoginRequest failed", error);
			KrollDict event = new KrollDict();
			event.put("success", false);
			event.put("message", error.getMessage());
			event.put("state", "login");
			triggerLogIn(event);

			if (progressDialog != null) {
				progressDialog.dismiss();
				progressDialog = null;
			}
		}
	}

	private final class FBQueryRequestDelegateImpl extends FBRequestDelegate {
		private final KrollCallback callback;

		FBQueryRequestDelegateImpl(KrollCallback callback) {
			this.callback = callback;
		}

		@Override
        public void requestDidLoad(FBRequest request, String contentType, Object result) {
			KrollDict event = new KrollDict();
			event.put("success", true);
			event.put("data", result);
			callback.call(event);
		}

		@Override
        public void requestDidFailWithError(FBRequest request, Throwable error) {
			KrollDict event = new KrollDict();
			event.put("success", false);
			event.put("message", error.getMessage());
			callback.call(event);
		}
	}
}
