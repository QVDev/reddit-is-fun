package talklittle.android.reddit;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;

import android.app.Activity;
import android.app.ListActivity;
import android.app.SearchManager;
import android.content.AsyncQueryHandler;
import android.content.Context;
import android.content.Intent;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.Contacts.People;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.AlphabetIndexer;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ResourceCursorAdapter;
import android.widget.SectionIndexer;
import android.widget.TextView;

/**
 * Class representing a Subreddit, i.e., a Thread List.
 * 
 * @author TalkLittle
 *
 */
public final class RedditIsFun extends ListActivity
		implements View.OnCreateContextMenuListener {

	
	public final String THREAD_KIND = "t3";
	public final String SERIALIZE_SEPARATOR = "\r";
	
    private static final String LIST_STATE_KEY = "liststate";
    private static final String FOCUS_KEY = "focused";
    
	private final JsonFactory jsonFactory = new JsonFactory(); 
	
    /** Custom list adapter that fits our rss data into the list. */
    private ThreadsListAdapter mAdapter;
    /** Url edit text field. */
    private EditText mUrlText;
    /** Status text field. */
    private TextView mStatusText;
    /** Handler used to post runnables to the UI thread. */
    private Handler mHandler;
    /** Currently running background network thread. */
    private ThreadsWorker mWorker;
//    /** Take this many chars from the front of the description. */
//    public static final int SNIPPET_LENGTH = 90;
    
//    static final String[] THREADS_PROJECTION = new String[] {
//        ThreadInfo.ID, // 0
//        ThreadInfo.TITLE, // 1
//        ThreadInfo.SCORE, // 2
//        ThreadInfo.DOMAIN, // 3
//        ThreadInfo.AUTHOR, // 4
//        ThreadInfo.CREATED, // 5
//        ThreadInfo.NUM_COMMENTS, // 6
//        SORT_STRING, // 7
//    };
    
    static final int ID_COLUMN_INDEX = 0;
    static final int TITLE_COLUMN_INDEX = 1;
    static final int LINKDOMAIN_COLUMN_INDEX = 2;
    static final int SUBMITTER_COLUMN_INDEX = 3;
    static final int SUBMISSIONTIME_COLUMN_INDEX = 4;
    static final int NUMCOMMENTS_COLUMN_INDEX = 5;
    static final int VOTES_COLUMN_INDEX = 6;
    static final int THUMBNAIL_COLUMN_INDEX = 7;
    
    private static final int QUERY_TOKEN = 42;
    
    private QueryHandler mQueryHandler;
    private String mQuery;
    private Uri mGroupFilterUri;
    private Uri mGroupUri;
    private boolean mJustCreated;
    private boolean mSyncEnabled;

    /**
     * Used to keep track of the scroll state of the list.
     */
    private Parcelable mListState = null;
    private boolean mListHasFocus;



    /**
     * Called when the activity starts up. Do activity initialization
     * here, not in a constructor.
     * 
     * @see Activity#onCreate
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.threads_list_content);
        // The above layout contains a list id "android:list"
        // which ListActivity adopts as its list -- we can
        // access it with getListView().

        // Install our custom RSSListAdapter.
        List<ThreadInfo> items = new ArrayList<ThreadInfo>();
        mAdapter = new ThreadsListAdapter(this);
        getListView().setAdapter(mAdapter);

        // Get pointers to the UI elements in the threads_list_content layout
        mUrlText = (EditText)findViewById(R.id.urltext);
        mStatusText = (TextView)findViewById(R.id.statustext);
        
        Button download = (Button)findViewById(R.id.download);
        download.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                doGetThreadsList(mUrlText.getText());
            }
        });

        // Need one of these to post things back to the UI thread.
        mHandler = new Handler();
        
        // NOTE: this could use the icicle as done in
        // onRestoreInstanceState().
    }

    private static final class QueryHandler extends AsyncQueryHandler {
        private final WeakReference<RedditIsFun> mActivity;

        public QueryHandler(Context context) {
            super(context.getContentResolver());
            mActivity = new WeakReference<RedditIsFun>((RedditIsFun) context);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            final RedditIsFun activity = mActivity.get();
            if (activity != null && !activity.isFinishing()) {
                activity.mAdapter.setLoading(false);
                activity.getListView().clearTextFilter();                
                activity.mAdapter.changeCursor(cursor);
                
                // Now that the cursor is populated again, it's possible to restore the list state
                if (activity.mListState != null) {
                    activity.mList.onRestoreInstanceState(activity.mListState);
                    if (activity.mListHasFocus) {
                        activity.mList.requestFocus();
                    }
                    activity.mListHasFocus = false;
                    activity.mListState = null;
                }
            } else {
                cursor.close();
            }
        }
    }

    final static class ThreadsListItemCache {
    	public TextView titleView;
    	public CharArrayBuffer titleBuffer = new CharArrayBuffer(128);
    	public TextView linkDomainView;
    	public CharArrayBuffer linkDomainBuffer = new CharArrayBuffer(128);
    	public TextView votesView;
    	public CharArrayBuffer votesBuffer = new CharArrayBuffer(128);
    	public TextView numCommentsView;
    	public CharArrayBuffer numCommentsBuffer = new CharArrayBuffer(128);
    	public TextView submissionTimeView;
    	public CharArrayBuffer submissionTimeBuffer = new CharArrayBuffer(128);
    	public TextView submitterView;
    	public CharArrayBuffer submitterBuffer = new CharArrayBuffer(128);
    	public ImageView thumbnailView;
    }

    private Uri getPeopleFilterUri(String filter) {
        if (!TextUtils.isEmpty(filter)) {
            return Uri.withAppendedPath(People.CONTENT_FILTER_URI, Uri.encode(filter));
        } else {
            return People.CONTENT_URI;
        }
    }

    private static String getSortOrder(String[] projectionType) {
    	return ThreadInfo.ID + " ASC";
    }
    
    void startQuery() {
        mAdapter.setLoading(true);
        
        // Cancel any pending queries
        mQueryHandler.cancelOperation(QUERY_TOKEN);

        // Kick off the new query
        mQueryHandler.startQuery(QUERY_TOKEN, null, People.CONTENT_URI, CONTACTS_PROJECTION,
                null, null, getSortOrder(CONTACTS_PROJECTION));
    }


//    /**
//     * Called from a background thread to do the filter and return the resulting cursor.
//     * 
//     * @param filter the text that was entered to filter on
//     * @return a cursor with the results of the filter
//     */
//    Cursor doFilter(String filter) {
//        final ContentResolver resolver = getContentResolver();
//
//        return resolver.query(getPeopleFilterUri(filter), CONTACTS_PROJECTION, null, null,
//        		getSortOrder(CONTACTS_PROJECTION));
//    }

    private final class ThreadsListAdapter extends ResourceCursorAdapter
    		implements SectionIndexer {
    	private SectionIndexer mIndexer;
    	private String mAlphabet;
        private Context mContext;
        private boolean mLoading = true;
//        private CharSequence mUnknownNameText;
//        private CharSequence[] mLocalizedLabels;
        private boolean mDisplayPhotos = false;
        private SparseArray<SoftReference<Bitmap>> mBitmapCache = null;
        private int mFrequentSeparatorPos = ListView.INVALID_POSITION;

        
        public ThreadsListAdapter(Context context) {
            super(context, R.layout.threads_list_item, null, false);
        }

        private SectionIndexer getNewIndexer(Cursor cursor) {
            return new AlphabetIndexer(cursor, TITLE_COLUMN_INDEX, mAlphabet);
        }
        
        /**
         * Callback on the UI thread when the content observer on the backing cursor fires.
         * Instead of calling requery we need to do an async query so that the requery doesn't
         * block the UI thread for a long time. 
         */
        @Override
        protected void onContentChanged() {
            CharSequence constraint = getListView().getTextFilter();
            if (!TextUtils.isEmpty(constraint)) {
                // Reset the filter state then start an async filter operation
                Filter filter = getFilter();
                filter.filter(constraint);
            } else {
                // Start an async query
            	// XXX
//                startQuery();
            }
        }
        
        public void setLoading(boolean loading) {
            mLoading = loading;
        }

        @Override
        public boolean isEmpty() {
            if (mLoading) {
                // We don't want the empty state to show when loading.
                return false;
            } else {
                return super.isEmpty();
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == mFrequentSeparatorPos) {
                // We don't want the separator view to be recycled.
                return IGNORE_ITEM_VIEW_TYPE;
            }
            return super.getItemViewType(position);
        }

        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view;

            // Here view may be passed in for re-use, or we make a new one.
            if (convertView == null) {
            	// XXX Cursor
                view = newView(mContext, getCursor(), parent);
            } else {
                view = convertView;
            }

            bindView(view, mContext, getCursor());
            return view;
        }
        
        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            final View view = super.newView(context, cursor, parent);

            ThreadsListItemCache cache = new ThreadsListItemCache();
            cache.titleView = (TextView) view.findViewById(R.id.title);
            cache.linkDomainView = (TextView) view.findViewById(R.id.linkDomain);
            cache.numCommentsView = (TextView) view.findViewById(R.id.numComments);
            cache.submissionTimeView = (TextView) view.findViewById(R.id.submissionTime);
            cache.submitterView = (TextView) view.findViewById(R.id.submitter);
            cache.votesView = (TextView) view.findViewById(R.id.votes);
            cache.thumbnailView = (ImageView) view.findViewById(R.id.thumbnail);

            return view;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final ThreadsListItemCache cache = (ThreadsListItemCache) view.getTag();
            
            int size;
            // Set the TextView fields
            cursor.copyStringToBuffer(TITLE_COLUMN_INDEX, cache.titleBuffer);
            size = cache.titleBuffer.sizeCopied;
            if (size != 0) {
                cache.titleView.setText(cache.titleBuffer.data, 0, size);
            } else {
                // TODO: Error. This shouldn't ever happen.
            }
            cursor.copyStringToBuffer(NUMCOMMENTS_COLUMN_INDEX, cache.numCommentsBuffer);
            size = cache.numCommentsBuffer.sizeCopied;
            if (size != 0) {
                cache.numCommentsView.setText(cache.numCommentsBuffer.data, 0, size);
            } else {
                // TODO: Error. This shouldn't ever happen.
            }
            cursor.copyStringToBuffer(LINKDOMAIN_COLUMN_INDEX, cache.linkDomainBuffer);
            size = cache.linkDomainBuffer.sizeCopied;
            if (size != 0) {
                cache.linkDomainView.setText(cache.linkDomainBuffer.data, 0, size);
            } else {
                // TODO: Error. This shouldn't ever happen.
            }
            cursor.copyStringToBuffer(SUBMITTER_COLUMN_INDEX, cache.submitterBuffer);
            size = cache.submitterBuffer.sizeCopied;
            if (size != 0) {
                cache.submitterView.setText(cache.submitterBuffer.data, 0, size);
            } else {
                // TODO: Error. This shouldn't ever happen.
            }
            cursor.copyStringToBuffer(SUBMISSIONTIME_COLUMN_INDEX, cache.submissionTimeBuffer);
            size = cache.submissionTimeBuffer.sizeCopied;
            if (size != 0) {
                cache.submissionTimeView.setText(cache.submissionTimeBuffer.data, 0, size);
            } else {
                // TODO: Error. This shouldn't ever happen.
            }
            cursor.copyStringToBuffer(VOTES_COLUMN_INDEX, cache.votesBuffer);
            size = cache.votesBuffer.sizeCopied;
            if (size != 0) {
                cache.votesView.setText(cache.votesBuffer.data, 0, size);
            } else {
                // TODO: Error. This shouldn't ever happen.
            }
            
            // TODO?: Reddit thread SEARCH query mode?
            // Bail out early if using a specific SEARCH query mode, usually for
            // matching a specific E-mail or phone number. Any contact details
            // shown would be identical, and columns might not even be present
            // in the returned cursor.
//            if (mQueryMode != QUERY_MODE_NONE) {
//                cache.numberView.setVisibility(View.GONE);
//                cache.labelView.setVisibility(View.GONE);
//                cache.presenceView.setVisibility(View.GONE);
//                return;
//            }
            

            // TODO: If thumbnail, download it and create ImageView
            // Set the proper icon (star or presence or nothing)
//            ImageView presenceView = cache.presenceView;
//            if ((mMode & MODE_MASK_NO_PRESENCE) == 0) {
//                int serverStatus;
//                if (!cursor.isNull(SERVER_STATUS_COLUMN_INDEX)) {
//                    serverStatus = cursor.getInt(SERVER_STATUS_COLUMN_INDEX);
//                    presenceView.setImageResource(
//                            Presence.getPresenceIconResourceId(serverStatus));
//                    presenceView.setVisibility(View.VISIBLE);
//                } else {
//                    presenceView.setVisibility(View.GONE);
//                }
//            } else {
//                presenceView.setVisibility(View.GONE);
//            }
//
//            // Set the photo, if requested
//            if (mDisplayPhotos) {
//                Bitmap photo = null;
//
//                // Look for the cached bitmap
//                int pos = cursor.getPosition();
//                SoftReference<Bitmap> ref = mBitmapCache.get(pos);
//                if (ref != null) {
//                    photo = ref.get();
//                }
//
//                if (photo == null) {
//                    // Bitmap cache miss, decode it from the cursor
//                    if (!cursor.isNull(PHOTO_COLUMN_INDEX)) {
//                        try {
//                            byte[] photoData = cursor.getBlob(PHOTO_COLUMN_INDEX);
//                            photo = BitmapFactory.decodeByteArray(photoData, 0,
//                                    photoData.length);
//                            mBitmapCache.put(pos, new SoftReference<Bitmap>(photo));
//                        } catch (OutOfMemoryError e) {
//                            // Not enough memory for the photo, use the default one instead
//                            photo = null;
//                        }
//                    }
//                }
//
//                // Bind the photo, or use the fallback no photo resource
//                if (photo != null) {
//                    cache.photoView.setImageBitmap(photo);
//                } else {
//                    cache.photoView.setImageResource(R.drawable.ic_contact_list_picture);
//                }
//            }
        }

        @Override
        public void changeCursor(Cursor cursor) {
        	// XXX no starred items for Reddit, yet
            // Get the split between starred and frequent items, if the mode is strequent
//            mFrequentSeparatorPos = ListView.INVALID_POSITION;
//            if (cursor != null && cursor.getCount() > 0 && mMode == MODE_STREQUENT) {
//                cursor.move(-1);
//                for (int i = 0; cursor.moveToNext(); i++) {
//                    int starred = cursor.getInt(STARRED_COLUMN_INDEX);
//                    if (starred == 0) {
//                        if (i > 0) {
//                            // Only add the separator when there are starred items present
//                            mFrequentSeparatorPos = i;
//                        }
//                        break;
//                    }
//                }
//            }

            super.changeCursor(cursor);

            // Update the indexer for the fast scroll widget
            updateIndexer(cursor);

            // Clear the photo bitmap cache, if there is one
            if (mBitmapCache != null) {
                mBitmapCache.clear();
            }
        }
        
        private void updateIndexer(Cursor cursor) {
            if (mIndexer == null) {
                mIndexer = getNewIndexer(cursor);
            } else {
                if (mIndexer instanceof AlphabetIndexer) {
                    ((AlphabetIndexer)mIndexer).setCursor(cursor);
                } else {
                    mIndexer = getNewIndexer(cursor);
                }
            }
        }
        
//        /**
//         * Run the query on a helper thread. Beware that this code does not run
//         * on the main UI thread!
//         */
//        @Override
//        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
//            return doFilter(constraint.toString());
//        }
        
        public Object [] getSections() {
            return mIndexer.getSections();
        }

        public int getPositionForSection(int sectionIndex) {
            if (mIndexer == null) {
                Cursor cursor = mAdapter.getCursor();
                if (cursor == null) {
                    // No cursor, the section doesn't exist so just return 0
                    return 0;
                }
                mIndexer = getNewIndexer(cursor);
            }

            return mIndexer.getPositionForSection(sectionIndex);
        }

        public int getSectionForPosition(int position) {
            // Note: JapaneseContactListIndexer depends on the fact
            // this method always returns 0. If you change this,
            // please care it too.
            return 0;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return true;
        }

        @Override
        public boolean isEnabled(int position) {
            return position != mFrequentSeparatorPos;
        }

        @Override
        public int getCount() {
            if (mFrequentSeparatorPos != ListView.INVALID_POSITION) {
                return super.getCount() + 1;
            } else {
                return super.getCount();
            }
        }
        
        private int getRealPosition(int pos) {
            if (mFrequentSeparatorPos == ListView.INVALID_POSITION) {
                // No separator, identity map
                return pos;
            } else if (pos <= mFrequentSeparatorPos) {
                // Before or at the separator, identity map
                return pos;
            } else {
                // After the separator, remove 1 from the pos to get the real underlying pos
                return pos - 1;
            }
            
        }
        
        @Override
        public Object getItem(int pos) {
            return super.getItem(getRealPosition(pos));
        }
        
        @Override
        public long getItemId(int pos) {
            return super.getItemId(getRealPosition(pos)); 
        }
    }


    /**
     * Called when user clicks an item in the list. Starts an activity to
     * open the url for that item.
     */
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        ThreadInfo item = (ThreadInfo) mAdapter.getItem(position);
        
        if ("self.reddit.com".equals(item.getLinkDomain())) {
            // TODO: new Intent aiming using CommentsListActivity specifically.
        } else {
            // TODO: popup dialog: 2 buttons: LINK, COMMENTS. well, 2 big and 2 small buttons (small: user profile, report post)
        	if ("link".equals("true")) {
	            // Creates and starts an intent to open the item.link url.
	            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.getLink().toString()));
	            startActivity(intent);
        	} else {
                // TODO: new Intent aiming using CommentsListActivity specifically.
        	}
        }
    }

    /**
     * Resets the output UI -- list and status text empty.
     */
    public void resetUI() {
        // Reset the list to be empty.
        List<ThreadInfo> items = new ArrayList<ThreadInfo>();
        mAdapter = new ThreadsListAdapter(this);
        getListView().setAdapter(mAdapter);

        mStatusText.setText("");
        mUrlText.requestFocus();
    }

    /**
     * Sets the currently active running worker. Interrupts any earlier worker,
     * so we only have one at a time.
     * 
     * @param worker the new worker
     */
    public synchronized void setCurrentWorker(ThreadsWorker worker) {
        if (mWorker != null) mWorker.interrupt();
        mWorker = worker;
    }

    /**
     * Is the given worker the currently active one.
     * 
     * @param worker
     * @return
     */
    public synchronized boolean isCurrentWorker(ThreadsWorker worker) {
        return (mWorker == worker);
    }


    /**
     * Given a subreddit name string, starts the threadlist-download-thread going.
     * 
     * @param subreddit The name of a subreddit ("reddit.com", "gaming", etc.) 
     */
    private void doGetThreadsList(CharSequence subreddit) {
    	ThreadsWorker worker = new ThreadsWorker(subreddit);
    	setCurrentWorker(worker);
    	
    	resetUI();
    	// TODO: nice status screen, like Alien vs. Android had
    	mStatusText.setText("Downloading\u2026");
    	
    	worker.start();
    }

    /**
     * Runnable that the worker thread uses to post ThreadItems to the
     * UI via mHandler.post
     */
    private class ItemAdder implements Runnable {
        ThreadInfo mItem;

        ItemAdder(ThreadInfo item) {
            mItem = item;
        }

        public void run() {
            mAdapter..add(mItem);
        }

        // NOTE: Performance idea -- would be more efficient to have he option
        // to add multiple items at once, so you get less "update storm" in the UI
        // compared to adding things one at a time.
    }

    /**
     * Worker thread takes in an rss url string, downloads its data, parses
     * out the rss items, and communicates them back to the UI as they are read.
     */
    private class ThreadsWorker extends Thread {
        private CharSequence mSubreddit;

        public ThreadsWorker(CharSequence subreddit) {
            mSubreddit = subreddit;
        }

        @Override
        public void run() {
            String status = "";
            try {
                // Standard code to make an HTTP connection.
                URL url = new URL("http://www.reddit.com/r/" + mSubreddit.toString() + "/.json");
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(10000);

                connection.connect();
                InputStream in = connection.getInputStream();
                
                parseSubredditJSON(in, mAdapter);
                status = "done";
            } catch (Exception e) {
                status = "failed:" + e.getMessage();
            }

            // Send status to UI (unless a newer worker has started)
            // To communicate back to the UI from a worker thread,
            // pass a Runnable to handler.post().
            final String temp = status;
            if (isCurrentWorker(this)) {
                mHandler.post(new Runnable() {
                    public void run() {
                        mStatusText.setText(temp);
                    }
                });
            }
        }
    }

    /**
     * Populates the menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        // FIXME: login/logout, goto subreddit, 
        menu.add(0, 0, 0, "Slashdot")
            .setOnMenuItemClickListener(new RSSMenu("http://rss.slashdot.org/Slashdot/slashdot"));

        menu.add(0, 0, 0, "Google News")
            .setOnMenuItemClickListener(new RSSMenu("http://news.google.com/?output=rss"));
        
        menu.add(0, 0, 0, "News.com")
            .setOnMenuItemClickListener(new RSSMenu("http://news.com.com/2547-1_3-0-20.xml"));

        menu.add(0, 0, 0, "Bad Url")
            .setOnMenuItemClickListener(new RSSMenu("http://nifty.stanford.edu:8080"));

        menu.add(0, 0, 0, "Reset")
                .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                resetUI();
                return true;
            }
        });

        return true;
    }

    /**
     * Puts text in the url text field and gives it focus. Used to make a Runnable
     * for each menu item. This way, one inner class works for all items vs. an
     * anonymous inner class for each menu item.
     */
    private class RSSMenu implements MenuItem.OnMenuItemClickListener {
        private CharSequence mUrl;

        RSSMenu(CharSequence url) {
            mUrl = url;
        }

        public boolean onMenuItemClick(MenuItem item) {
            mUrlText.setText(mUrl);
            mUrlText.requestFocus();
            return true;
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();

        // The cursor was killed off in onStop(), so we need to get a new one here
        // We do not perform the query if a filter is set on the list because the
        // filter will cause the query to happen anyway
        if (TextUtils.isEmpty(getListView().getTextFilter())) {
        	// XXX
//            startQuery();
        } else {
            // Run the filtered query on the adapter
            ((ThreadsListAdapter) getListAdapter()).onContentChanged();
        }
    }

    
    /**
     * Called for us to save out our current state before we are paused,
     * such a for example if the user switches to another app and memory
     * gets scarce. The given outState is a Bundle to which we can save
     * objects, such as Strings, Integers or lists of Strings. In this case, we
     * save out the list of currently downloaded rss data, (so we don't have to
     * re-do all the networking just because the user goes back and forth
     * between aps) which item is currently selected, and the data for the text views.
     * In onRestoreInstanceState() we look at the map to reconstruct the run-state of the
     * application, so returning to the activity looks seamlessly correct.
     * TODO: the Activity javadoc should give more detail about what sort of
     * data can go in the outState map.
     * 
     * @see android.app.Activity#onSaveInstanceState
     */
    @SuppressWarnings("unchecked")
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        // TODO: Save instance state
//        // Make a List of all the ThreadItem data for saving
//        // NOTE: there may be a way to save the ThreadItems directly,
//        // rather than their string data.
//        int count = mAdapter.getCount();
//
//        // Save out the items as a flat list of CharSequence objects --
//        // title0, link0, descr0, title1, link1, ...
//        ArrayList<CharSequence> strings = new ArrayList<CharSequence>();
//        for (int i = 0; i < count; i++) {
//            ThreadInfo item = mAdapter.getItem(i);
//            for (int j = 0; j < ThreadInfo._KEYS.length; j++) {
//            	if (item.mValues.containsKey(ThreadInfo._KEYS[i])) {
//            		strings.add(ThreadInfo._KEYS[i]);
//            		strings.add(item.mValues.get(ThreadInfo._KEYS[i]));
//            	}
//            }
//            strings.add(SERIALIZE_SEPARATOR);
//        }
//        outState.putSerializable(STRINGS_KEY, strings);
//
//        // Save current selection index (if focussed)
//        if (getListView().hasFocus()) {
//            outState.putInt(SELECTION_KEY, Integer.valueOf(getListView().getSelectedItemPosition()));
//        }
//
//        // Save url
//        outState.putString(URL_KEY, mUrlText.getText().toString());
//        
//        // Save status
//        outState.putCharSequence(STATUS_KEY, mStatusText.getText());
    }
    
    

    /**
     * Called to "thaw" re-animate the app from a previous onSaveInstanceState().
     * 
     * @see android.app.Activity#onRestoreInstanceState
     */
    @SuppressWarnings("unchecked")
    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);

        // TODO: Restore instance state
//        // Note: null is a legal value for onRestoreInstanceState.
//        if (state == null) return;
//
//        // Restore items from the big list of CharSequence objects
//        List<CharSequence> strings = (ArrayList<CharSequence>)state.getSerializable(STRINGS_KEY);
//        List<ThreadInfo> items = new ArrayList<ThreadInfo>();
//        for (int i = 0; i < strings.size(); i++) {
//        	ThreadInfo ti = new ThreadInfo();
//        	CharSequence key, value;
//        	while (!SERIALIZE_SEPARATOR.equals(strings.get(i))) {
//        		if (SERIALIZE_SEPARATOR.equals(strings.get(i+1))) {
//        			// Well, just skip the value instead of throwing an exception.
//        			break;
//        		}
//        		key = strings.get(i);
//        		value = strings.get(i+1);
//        		ti.put(key.toString(), value.toString());
//        		i += 2;
//        	}
//            items.add(ti);
//        }
//
//        // Reset the list view to show this data.
//        mAdapter = new ThreadsListAdapter(this);
//        getListView().setAdapter(mAdapter);
//
//        // Restore selection
//        if (state.containsKey(SELECTION_KEY)) {
//            getListView().requestFocus(View.FOCUS_FORWARD);
//            // todo: is above right? needed it to work
//            getListView().setSelection(state.getInt(SELECTION_KEY));
//        }
//        
//        // Restore url
//        mUrlText.setText(state.getCharSequence(URL_KEY));
//        
//        // Restore status
//        mStatusText.setText(state.getCharSequence(STATUS_KEY));
    }


    /**
     * Skips ahead the JsonParser while validating.
     * When the function returns normally, JsonParser.getCurrentToken() should return the
     * JsonToken.START_ARRAY value corresponding to the beginning of the list of threads.
     * @param jp
     * @throws JsonParseException
     * @throws IllegalStateException
     */
    void skipToThreadsJSON(JsonParser jp) throws IOException, JsonParseException, IllegalStateException {
    	String genericListingError = "Not a subreddit listing";
    	if (JsonToken.START_OBJECT != jp.nextToken()) // starts with "{"
    		throw new IllegalStateException(genericListingError);
    	jp.nextToken();
    	if (!"kind".equals(jp.getCurrentName()))
    		throw new IllegalStateException(genericListingError);
    	jp.nextToken();
    	if (!"Listing".equals(jp.getText()))
    		throw new IllegalStateException(genericListingError);
    	jp.nextToken();
    	if (!"data".equals(jp.getCurrentName()))
    		throw new IllegalStateException(genericListingError);
    	jp.nextToken();
    	if (JsonToken.START_OBJECT != jp.getCurrentToken())
    		throw new IllegalStateException(genericListingError);
    	jp.nextToken();
    	while (!"children".equals(jp.getCurrentName())) {
    		// Don't care
    		jp.nextToken();
    	}
    	jp.nextToken();
    	if (jp.getCurrentToken() != JsonToken.START_ARRAY)
    		throw new IllegalStateException(genericListingError);
    }
    
    void parseSubredditJSON(InputStream in, ThreadsListAdapter adapter) throws IOException,
		    JsonParseException, IllegalStateException {
		
		JsonParser jp = jsonFactory.createJsonParser(in);
		
		// Validate initial stuff, skip to the JSON List of threads
		skipToThreadsJSON(jp);

		while (jp.nextToken() != JsonToken.END_ARRAY) {
			if (jp.getCurrentToken() != JsonToken.START_OBJECT)
				throw new IllegalStateException("Unexpected non-JSON-object in the children array");
			
			// Process JSON representing one thread
			while (jp.nextToken() != JsonToken.END_OBJECT) {
				String fieldname = jp.getCurrentName();
				jp.nextToken(); // move to value, or START_OBJECT/START_ARRAY
			
				if ("data".equals(fieldname)) { // contains an object
					ThreadInfo ti = new ThreadInfo();
					while (jp.nextToken() != JsonToken.END_OBJECT) {
						String namefield = jp.getCurrentName();
						jp.nextToken(); // move to value
						// TODO: validate each field but I'm lazy
						ti.put(namefield, jp.getText());
					}
					mHandler.post(new ItemAdder(ti));
				} else if ("kind".equals(fieldname)) {
					if (!THREAD_KIND.equals(jp.getText())) {
						// Skip this JSON Object since it doesn't represent a thread.
						// May encounter nested objects too.
						int nested = 0;
						for (;;) {
							jp.nextToken();
							if (jp.getCurrentToken() == JsonToken.END_OBJECT && nested == 0)
								break;
							if (jp.getCurrentToken() == JsonToken.START_OBJECT)
								nested++;
							if (jp.getCurrentToken() == JsonToken.END_OBJECT)
								nested--;
						}
						break;  // Go on to the next JSON Object in the JSON Array which should hold threads.
					}
				} else {
					throw new IllegalStateException("Unrecognized field '"+fieldname+"'!");
				}
			}
		}
	}
    

}