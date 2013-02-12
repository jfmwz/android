package com.twofours.surespot.friends;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.text.InputFilter;
import android.text.method.TextKeyListener;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import ch.boye.httpclientandroidlib.client.HttpResponseException;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.twofours.surespot.IdentityController;
import com.twofours.surespot.LetterOrDigitInputFilter;
import com.twofours.surespot.MultiProgressDialog;
import com.twofours.surespot.R;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.activities.LoginActivity;
import com.twofours.surespot.activities.SettingsActivity;
import com.twofours.surespot.chat.ChatActivity;
import com.twofours.surespot.chat.ChatController;
import com.twofours.surespot.chat.ChatUtils;
import com.twofours.surespot.chat.IConnectCallback;
import com.twofours.surespot.chat.SurespotMessage;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.network.IAsyncCallback;

public class FriendActivity extends SherlockActivity {
	private FriendAdapter mMainAdapter;
	private static final String TAG = "FriendActivity";
	private static final int REQUEST_SETTINGS = 1;
	private MultiProgressDialog mMpdInviteFriend;
	private BroadcastReceiver mInvitationReceiver;
	private BroadcastReceiver InviteResponseReceiver;
	private BroadcastReceiver mMessageReceiver;
	private HashMap<String, Integer> mLastViewedMessageIds;
	private HashMap<String, Integer> mUnsentCount;
	private ChatController mChatController;
	private ListView mListView;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		SurespotLog.v(TAG, "onCreate");

		Utils.configureActionBar(this, null, null, false);

		mChatController = new ChatController(new IConnectCallback() {

			@Override
			public void connectStatus(boolean status) {
				if (!status) {
					SurespotLog.w(TAG, "Could not connect to chat server.");
				}
			}
		});
		setContentView(R.layout.activity_friend);
		mLastViewedMessageIds = new HashMap<String, Integer>();
		mMpdInviteFriend = new MultiProgressDialog(this, "inviting friend", 750);

		mListView = (ListView) findViewById(R.id.main_list);
		mListView.setEmptyView(findViewById(R.id.progressBar));
		// findViewById(R.id.progressBar).setVisibility(View.VISIBLE);
		mMainAdapter = new FriendAdapter(this);

		// click on friend to join chat
		mListView.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				Friend friend = (Friend) mMainAdapter.getItem(position);
				if (friend.isFriend()) {
					// start chat activity
					Intent newIntent = new Intent(FriendActivity.this, ChatActivity.class);
					newIntent.putExtra(SurespotConstants.ExtraNames.SHOW_CHAT_NAME, friend.getName());
					// if we have a send intent, when we pick a user, propogate it
					// Get intent, action and MIME type
					Intent intent = getIntent();
					String action = intent.getAction();
					String type = intent.getType();

					if ((Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) && type != null) {
						newIntent.setAction(action);
						newIntent.setType(type);
						newIntent.putExtras(intent);

						// remove intent data so we don't ask user to select a user again
						intent.setAction(null);
						intent.setType(null);
					}

					FriendActivity.this.startActivity(newIntent);
				}
			}
		});

		Button addFriendButton = (Button) findViewById(R.id.bAddFriend);
		addFriendButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				inviteFriend();
			}
		});

		InviteResponseReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String jsonStringInviteResponse = intent.getStringExtra(SurespotConstants.ExtraNames.INVITE_RESPONSE);
				JSONObject jsonInviteResponse;
				try {
					jsonInviteResponse = new JSONObject(jsonStringInviteResponse);
					String name = jsonInviteResponse.getString("user");
					if (jsonInviteResponse.getString("response").equals("accept")) {
						mMainAdapter.addNewFriend(name);
					}
					else {
						mMainAdapter.removeFriend(name);
					}
				}
				catch (JSONException e) {
					SurespotLog.w(TAG, "onReceive (inviteResponse)", e);
				}

			}
		};

		// register for invites
		mInvitationReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				mMainAdapter.addFriendInviter(intent.getStringExtra(SurespotConstants.ExtraNames.NAME));
			}
		};

		mMessageReceiver = new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				String message = intent.getExtras().getString(SurespotConstants.ExtraNames.MESSAGE);

				JSONObject messageJson;
				try {
					messageJson = new JSONObject(message);
					String name = ChatUtils.getOtherUser(messageJson.getString("from"), messageJson.getString("to"));
					Integer id = messageJson.getInt("id");

					int serverId = 0;
					if (id != null) {
						serverId = id;
					}

					Integer localId = mLastViewedMessageIds.get(name);
					if (localId == null) {
						localId = 0;
					}
					// SurespotLog.v(TAG, "last localId for " + user + ": " + localId);
					// SurespotLog.v(TAG, "last serverId for " + user + ": " + serverId);

					// compute delta
					int messageDelta = serverId - localId;
					Integer unsent1 = mUnsentCount.get(name);
					messageDelta = messageDelta - (unsent1 == null ? 0 : unsent1);
					mMainAdapter.messageDeltaReceived(name, messageDelta > 0 ? messageDelta : 0);

				}
				catch (JSONException e) {
					SurespotLog.w(TAG, "onReceive (message)", e);
				}
			}
		};

		EditText editText = (EditText) findViewById(R.id.etFriend);
		editText.setFilters(new InputFilter[] { new LetterOrDigitInputFilter() });
		editText.setOnEditorActionListener(new OnEditorActionListener() {
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				boolean handled = false;
				if (actionId == EditorInfo.IME_ACTION_DONE) {
					//
					inviteFriend();
					handled = true;
				}
				return handled;
			}
		});
	}

	@Override
	public void onResume() {
		super.onResume();
		SurespotLog.v(TAG, "onResume");

		LocalBroadcastManager.getInstance(this).registerReceiver(InviteResponseReceiver,
				new IntentFilter(SurespotConstants.IntentFilters.INVITE_RESPONSE));
		LocalBroadcastManager.getInstance(this).registerReceiver(mInvitationReceiver,
				new IntentFilter(SurespotConstants.IntentFilters.INVITE_REQUEST));
		LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver,
				new IntentFilter(SurespotConstants.IntentFilters.MESSAGE_RECEIVED));

		Intent intent = getIntent();
		String action = intent.getAction();
		String type = intent.getType();

		if ((Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) && type != null) {
			// set the title
			Utils.setActionBarTitles(this, "send", "select recipient");
		}
		else {
			Utils.setActionBarTitles(this, "home", IdentityController.getLoggedInUser());
		}

		mLastViewedMessageIds = SurespotApplication.getStateController().loadLastViewMessageIds();
		// get unsent messages out of shared prefs

		List<SurespotMessage> unsentMessages = SurespotApplication.getStateController().loadUnsentMessages();
		mUnsentCount = new HashMap<String, Integer>();
		Iterator<SurespotMessage> iterator = unsentMessages.iterator();
		while (iterator.hasNext()) {
			SurespotMessage message = iterator.next();
			String otherUser = ChatUtils.getOtherUser(message.getFrom(), message.getTo());
			Integer count = mUnsentCount.get(otherUser);
			if (count == null) {
				count = 1;
			}
			else {
				count++;
			}

			mUnsentCount.put(otherUser, count);

		}

		mChatController.connect();

		// get the list of friends
		SurespotApplication.getNetworkController().getFriends(new JsonHttpResponseHandler() {
			@Override
			public void onSuccess(JSONArray jsonArray) {
				SurespotLog.v(TAG, "getFriends success.");

				if (jsonArray.length() > 0) {

					mMainAdapter.refreshActiveChats();
					// mMainAdapter.clearFriends(false);
					mMainAdapter.addFriends(jsonArray);

					// compute new message deltas
					SurespotApplication.getNetworkController().getLastMessageIds(new JsonHttpResponseHandler() {

						public void onSuccess(int arg0, JSONObject arg1) {
							SurespotLog.v(TAG, "getLastmessageids success status jsonobject");

							HashMap<String, Integer> serverMessageIds = Utils.jsonToMap(arg1);

							if (serverMessageIds != null && serverMessageIds.size() > 0) {
								// if we have counts
								if (mLastViewedMessageIds != null) {

									// set the deltas
									for (String user : serverMessageIds.keySet()) {

										// figure out new message counts
										int serverId = serverMessageIds.get(user);
										Integer localId = mLastViewedMessageIds.get(user);
										// SurespotLog.v(TAG, "last localId for " + user + ": " + localId);
										// SurespotLog.v(TAG, "last serverId for " + user + ": " + serverId);

										// new chat, all messages are new
										if (localId == null) {
											mLastViewedMessageIds.put(user, serverId);
											mMainAdapter.messageDeltaReceived(user, serverId);
										}
										else {

											// compute delta
											int messageDelta = serverId - localId;
											Integer unsent1 = mUnsentCount.get(user);
											messageDelta = messageDelta - (unsent1 == null ? 0 : unsent1);
											mMainAdapter.messageDeltaReceived(user, messageDelta > 0 ? messageDelta : 0);
										}
									}

								}

								// if this is first time through store the last message ids
								else {
									mLastViewedMessageIds = serverMessageIds;

								}
							}
							else {
								SurespotLog.v(TAG, "No conversations.");
							}
							mMainAdapter.sort();

							((ListView) findViewById(R.id.main_list)).setEmptyView(findViewById(R.id.main_list_empty));
							mListView.setAdapter(mMainAdapter);
							findViewById(R.id.progressBar).setVisibility(View.GONE);
						};

						public void onFailure(Throwable arg0, String arg1) {
							// if we didn't get a 401
							if (!SurespotApplication.getNetworkController().isUnauthorized()) {

								SurespotLog.w(TAG, "getLastMessageIds: " + arg0.toString(), arg0);

								((ListView) findViewById(R.id.main_list)).setEmptyView(findViewById(R.id.main_list_empty));
								findViewById(R.id.progressBar).setVisibility(View.GONE);
								mListView.setAdapter(mMainAdapter);

							}
						};
					});
				}
				else {
					mListView.setAdapter(mMainAdapter);
					((ListView) findViewById(R.id.main_list)).setEmptyView(findViewById(R.id.main_list_empty));
					findViewById(R.id.progressBar).setVisibility(View.GONE);
				}
			}

			@Override
			public void onFailure(Throwable arg0, String content) {
				// if we didn't get a 401
				if (!SurespotApplication.getNetworkController().isUnauthorized()) {

					SurespotLog.w(TAG, "getFriends: " + content, arg0);

					Utils.makeToast(FriendActivity.this, "Could not load friends, please try again later.");
					// TODO show error / go back to login

					((ListView) findViewById(R.id.main_list)).setEmptyView(findViewById(R.id.main_list_empty));
					findViewById(R.id.progressBar).setVisibility(View.GONE);
					mListView.setAdapter(mMainAdapter);
				}
			}
		});
	}

	@Override
	protected void onPause() {
		super.onPause();
		SurespotLog.v(TAG, "onPause");

		LocalBroadcastManager.getInstance(this).unregisterReceiver(InviteResponseReceiver);
		LocalBroadcastManager.getInstance(this).unregisterReceiver(mInvitationReceiver);
		LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);

		Utils.putSharedPrefsString(this, SurespotConstants.PrefNames.LAST_CHAT, null);

		// put the active chats in if we've fucked with them
		SurespotApplication.getStateController().saveActiveChats(mMainAdapter.getActiveChats());
		// Utils.putSharedPrefsString(this, SurespotConstants.PrefNames.PREFS_ACTIVE_CHATS, jsonArray.toString());

		mChatController.disconnect();
		mChatController.destroy();
	}

	private void inviteFriend() {
		final EditText etFriend = ((EditText) findViewById(R.id.etFriend));
		final String friend = etFriend.getText().toString();

		if (friend.length() > 0) {
			if (friend.equals(IdentityController.getLoggedInUser())) {
				// TODO let them be friends with themselves?
				Utils.makeToast(this, "You can't be friends with yourself, bro.");
				return;
			}

			mMpdInviteFriend.incrProgress();
			SurespotApplication.getNetworkController().invite(friend, new AsyncHttpResponseHandler() {
				@Override
				public void onSuccess(int statusCode, String arg0) { // TODO
																		// indicate
																		// in
																		// the
																		// UI
					// that the request is
					// pending somehow
					TextKeyListener.clear(etFriend.getText());
					mMainAdapter.addFriendInvited(friend);
					Utils.makeToast(FriendActivity.this, friend + " has been invited to be your friend.");
				}

				@Override
				public void onFailure(Throwable arg0, String content) {
					if (arg0 instanceof HttpResponseException) {
						HttpResponseException error = (HttpResponseException) arg0;
						int statusCode = error.getStatusCode();
						switch (statusCode) {
						case 404:
							Utils.makeToast(FriendActivity.this, "User does not exist.");
							break;
						case 409:
							Utils.makeToast(FriendActivity.this, "You are already friends.");
							break;
						case 403:
							Utils.makeToast(FriendActivity.this, "You have already invited this user.");
							break;
						default:
							SurespotLog.w(TAG, "inviteFriend: " + content, arg0);
							Utils.makeToast(FriendActivity.this, "Could not invite friend, please try again later.");
						}
					}
					else {
						SurespotLog.w(TAG, "inviteFriend: " + content, arg0);
						Utils.makeToast(FriendActivity.this, "Could not invite friend, please try again later.");
					}
				}

				@Override
				public void onFinish() {
					mMpdInviteFriend.decrProgress();
				}
			});
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getSupportMenuInflater();
		inflater.inflate(R.menu.activity_friend, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_debug_clear:
			// clear out some shiznit
			for (String chatname : mMainAdapter.getFriends()) {
				Utils.putSharedPrefsString(this, "messages_" + chatname, null);

			}
			SurespotApplication.getStateController().saveActiveChats(null);
			SurespotApplication.getStateController().saveLastViewedMessageIds(null);
			SurespotApplication.getStateController().saveUnsentMessages(null);
			Utils.putSharedPrefsString(this, SurespotConstants.PrefNames.LAST_CHAT, null);

			// clear cache
			SurespotApplication.getNetworkController().clearCache();
			return true;
		case R.id.menu_logout:
			IdentityController.logout(this, new IAsyncCallback<Boolean>() {
				@Override
				public void handleResponse(Boolean result) {
					Intent intent = new Intent(FriendActivity.this, LoginActivity.class);
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					FriendActivity.this.startActivity(intent);
					Utils.putSharedPrefsString(FriendActivity.this, SurespotConstants.PrefNames.LAST_CHAT, null);
					finish();
				}
			});

			return true;

		case R.id.menu_settings:
			Intent intent = new Intent(this, SettingsActivity.class);
			startActivityForResult(intent, REQUEST_SETTINGS);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}

	}

}
