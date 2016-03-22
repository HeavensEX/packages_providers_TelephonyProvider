/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.providers.telephony;

import com.google.android.mms.ContentType;
import com.google.android.mms.pdu.CharacterSets;

import com.android.internal.annotations.VisibleForTesting;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.IntentService;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;
import android.util.SparseArray;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/***
 * Backup agent for backup and restore SMS's and text MMS's.
 *
 * This backup agent stores SMS's into "sms_backup" file as a JSON array. Example below.
 *  [{"self_phone":"+1234567891011","address":"+1234567891012","body":"Example sms",
 *  "date":"1450893518140","date_sent":"1450893514000","status":"-1","type":"1"},
 *  {"self_phone":"+1234567891011","address":"12345","body":"Example 2","date":"1451328022316",
 *  "date_sent":"1451328018000","status":"-1","type":"1"}]
 *
 * Text MMS's are stored into "mms_backup" file as a JSON array. Example below.
 *  [{"self_phone":"+1234567891011","date":"1451322716","date_sent":"0","m_type":"128","v":"18",
 *  "msg_box":"2","mms_addresses":[{"type":137,"address":"+1234567891011","charset":106},
 *  {"type":151,"address":"example@example.com","charset":106}],"mms_body":"Mms to email",
 *  "mms_charset":106},
 *  {"self_phone":"+1234567891011","sub":"MMS subject","date":"1451322955","date_sent":"0",
 *  "m_type":"132","v":"17","msg_box":"1","ct_l":"http://promms/servlets/NOK5BBqgUHAqugrQNM",
 *  "mms_addresses":[{"type":151,"address":"+1234567891011","charset":106}],
 *  "mms_body":"Mms\nBody\r\n",
 *  "mms_charset":106,"sub_cs":"106"}]
 *
 *   It deflates the files on the flight.
 *   Every 1000 messages it backs up file, deletes it and creates a new one with the same name.
 *
 *   It stores how many bytes we are over the quota and don't backup the oldest messages.
 */

@TargetApi(Build.VERSION_CODES.M)
public class TelephonyBackupAgent extends BackupAgent {
    private static final String TAG = "TelephonyBackupAgent";
    private static final boolean DEBUG = false;


    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private static final int DEFAULT_DURATION = 5000; //ms

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    @VisibleForTesting
    static final String sSmilTextOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Text\" top=\"0\" left=\"0\" "
                        + "height=\"100%%\" width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    @VisibleForTesting
    static final String sSmilTextPart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<text src=\"%s\" region=\"Text\" />" +
            "</par>";


    // JSON key for phone number a message was sent from or received to.
    private static final String SELF_PHONE_KEY = "self_phone";
    // JSON key for list of addresses of MMS message.
    private static final String MMS_ADDRESSES_KEY = "mms_addresses";
    // JSON key for list of recipients of the message.
    private static final String RECIPIENTS = "recipients";
    // JSON key for MMS body.
    private static final String MMS_BODY_KEY = "mms_body";
    // JSON key for MMS charset.
    private static final String MMS_BODY_CHARSET_KEY = "mms_charset";

    // File names suffixes for backup/restore.
    private static final String SMS_BACKUP_FILE_SUFFIX = "_sms_backup";
    private static final String MMS_BACKUP_FILE_SUFFIX = "_mms_backup";

    // File name formats for backup. It looks like 000000_sms_backup, 000001_sms_backup, etc.
    private static final String SMS_BACKUP_FILE_FORMAT = "%06d"+SMS_BACKUP_FILE_SUFFIX;
    private static final String MMS_BACKUP_FILE_FORMAT = "%06d"+MMS_BACKUP_FILE_SUFFIX;

    // Charset being used for reading/writing backup files.
    private static final String CHARSET_UTF8 = "UTF-8";

    // Order by ID entries from database.
    private static final String ORDER_BY_ID = BaseColumns._ID + " ASC";

    // Order by Date entries from database. We start backup from the oldest.
    private static final String ORDER_BY_DATE = "date ASC";

    // Columns from SMS database for backup/restore.
    @VisibleForTesting
    static final String[] SMS_PROJECTION = new String[] {
            Telephony.Sms._ID,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.SUBJECT,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.STATUS,
            Telephony.Sms.TYPE,
            Telephony.Sms.THREAD_ID
    };

    // Columns to fetch recepients of SMS.
    private static final String[] SMS_RECIPIENTS_PROJECTION = {
            Telephony.Threads._ID,
            Telephony.Threads.RECIPIENT_IDS
    };

    // Columns from MMS database for backup/restore.
    @VisibleForTesting
    static final String[] MMS_PROJECTION = new String[] {
            Telephony.Mms._ID,
            Telephony.Mms.SUBSCRIPTION_ID,
            Telephony.Mms.SUBJECT,
            Telephony.Mms.SUBJECT_CHARSET,
            Telephony.Mms.DATE,
            Telephony.Mms.DATE_SENT,
            Telephony.Mms.MESSAGE_TYPE,
            Telephony.Mms.MMS_VERSION,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.CONTENT_LOCATION,
            Telephony.Mms.THREAD_ID
    };

    // Columns from addr database for backup/restore. This database is used for fetching addresses
    // for MMS message.
    @VisibleForTesting
    static final String[] MMS_ADDR_PROJECTION = new String[] {
            Telephony.Mms.Addr.TYPE,
            Telephony.Mms.Addr.ADDRESS,
            Telephony.Mms.Addr.CHARSET
    };

    // Columns from part database for backup/restore. This database is used for fetching body text
    // and charset for MMS message.
    @VisibleForTesting
    static final String[] MMS_TEXT_PROJECTION = new String[] {
            Telephony.Mms.Part.TEXT,
            Telephony.Mms.Part.CHARSET
    };
    static final int MMS_TEXT_IDX = 0;
    static final int MMS_TEXT_CHARSET_IDX = 1;

    // Buffer size for Json writer.
    public static final int WRITER_BUFFER_SIZE = 32*1024; //32Kb

    // We increase how many bytes backup size over quota by 10%, so we will fit into quota on next
    // backup
    public static final double BYTES_OVER_QUOTA_MULTIPLIER = 1.1;

    // Maximum messages for one backup file. After reaching the limit the agent backs up the file,
    // deletes it and creates a new one with the same name.
    // Not final for the testing.
    @VisibleForTesting
    int mMaxMsgPerFile = 1000;


    // Default values for SMS, MMS, Addresses restore.
    private static final ContentValues sDefaultValuesSms = new ContentValues(3);
    private static final ContentValues sDefaultValuesMms = new ContentValues(5);
    private static final ContentValues sDefaultValuesAddr = new ContentValues(2);

    // Shared preferences for the backup agent.
    private static final String BACKUP_PREFS = "backup_shared_prefs";
    // Key for storing quota bytes.
    private static final String QUOTA_BYTES = "backup_quota_bytes";
    // Key for storing backup data size.
    private static final String BACKUP_DATA_BYTES = "backup_data_bytes";
    // Key for storing timestamp when backup agent resets quota. It does that to get onQuotaExceeded
    // call so it could get the new quota if it changed.
    private static final String QUOTA_RESET_TIME = "reset_quota_time";
    private static final long QUOTA_RESET_INTERVAL = 30 * AlarmManager.INTERVAL_DAY; // 30 days.


    static {
        // Consider restored messages read and seen.
        sDefaultValuesSms.put(Telephony.Sms.READ, 1);
        sDefaultValuesSms.put(Telephony.Sms.SEEN, 1);
        // If there is no sub_id with self phone number on restore set it to -1.
        sDefaultValuesSms.put(Telephony.Sms.SUBSCRIPTION_ID, -1);

        sDefaultValuesMms.put(Telephony.Mms.READ, 1);
        sDefaultValuesMms.put(Telephony.Mms.SEEN, 1);
        sDefaultValuesMms.put(Telephony.Mms.SUBSCRIPTION_ID, -1);
        sDefaultValuesMms.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_ALL);
        sDefaultValuesMms.put(Telephony.Mms.TEXT_ONLY, 1);

        sDefaultValuesAddr.put(Telephony.Mms.Addr.TYPE, 0);
        sDefaultValuesAddr.put(Telephony.Mms.Addr.CHARSET, CharacterSets.DEFAULT_CHARSET);
    }


    private SparseArray<String> mSubId2phone = new SparseArray<String>();
    private Map<String, Integer> mPhone2subId = new ArrayMap<String, Integer>();

    private ContentResolver mContentResolver;
    // How many bytes we can backup to fit into quota.
    private long mBytesOverQuota;

    // Cache list of recipients by threadId. It reduces db requests heavily. Used during backup.
    @VisibleForTesting
    Map<Long, List<String>> mCacheRecipientsByThread = null;
    // Cache threadId by list of recipients. Used during restore.
    @VisibleForTesting
    Map<Set<String>, Long> mCacheGetOrCreateThreadId = null;

    @Override
    public void onCreate() {
        super.onCreate();

        final SubscriptionManager subscriptionManager = SubscriptionManager.from(this);
        if (subscriptionManager != null) {
            final List<SubscriptionInfo> subInfo =
                    subscriptionManager.getActiveSubscriptionInfoList();
            if (subInfo != null) {
                for (SubscriptionInfo sub : subInfo) {
                    final String phoneNumber = getNormalizedNumber(sub);
                    mSubId2phone.append(sub.getSubscriptionId(), phoneNumber);
                    mPhone2subId.put(phoneNumber, sub.getSubscriptionId());
                }
            }
        }
        mContentResolver = getContentResolver();
    }

    @VisibleForTesting
    void setContentResolver(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }
    @VisibleForTesting
    void setSubId(SparseArray<String> subId2Phone, Map<String, Integer> phone2subId) {
        mSubId2phone = subId2Phone;
        mPhone2subId = phone2subId;
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        SharedPreferences sharedPreferences = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE);
        if (sharedPreferences.getLong(QUOTA_RESET_TIME, Long.MAX_VALUE) <
                System.currentTimeMillis()) {
            clearSharedPreferences();
        }

        mBytesOverQuota = sharedPreferences.getLong(BACKUP_DATA_BYTES, 0) -
                sharedPreferences.getLong(QUOTA_BYTES, Long.MAX_VALUE);
        if (mBytesOverQuota > 0) {
            mBytesOverQuota *= BYTES_OVER_QUOTA_MULTIPLIER;
        }

        try (
                Cursor smsCursor = mContentResolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                        null, null, ORDER_BY_DATE);
                // Do not backup non text-only MMS's.
                Cursor mmsCursor = mContentResolver.query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION,
                        Telephony.Mms.TEXT_ONLY+"=1", null, ORDER_BY_DATE)) {

            if (smsCursor != null) {
                smsCursor.moveToFirst();
            }
            if (mmsCursor != null) {
                mmsCursor.moveToFirst();
            }

            // It backs up messages from the oldest to newest. First it looks at the timestamp of
            // the next SMS messages and MMS message. If the SMS is older it backs up 1000 SMS
            // messages, otherwise 1000 MMS messages. Repeat until out of SMS's or MMS's.
            // It ensures backups are incremental.
            int fileNum = 0;
            while (smsCursor != null && !smsCursor.isAfterLast() &&
                    mmsCursor != null && !mmsCursor.isAfterLast()) {
                final long smsDate = TimeUnit.MILLISECONDS.toSeconds(getMessageDate(smsCursor));
                final long mmsDate = getMessageDate(mmsCursor);
                if (smsDate < mmsDate) {
                    backupAll(data, smsCursor, String.format(SMS_BACKUP_FILE_FORMAT, fileNum++));
                } else {
                    backupAll(data, mmsCursor, String.format(MMS_BACKUP_FILE_FORMAT, fileNum++));
                }
            }

            while (smsCursor != null && !smsCursor.isAfterLast()) {
                backupAll(data, smsCursor, String.format(SMS_BACKUP_FILE_FORMAT, fileNum++));
            }

            while (mmsCursor != null && !mmsCursor.isAfterLast()) {
                backupAll(data, mmsCursor, String.format(MMS_BACKUP_FILE_FORMAT, fileNum++));
            }
        }
    }

    @VisibleForTesting
    void clearSharedPreferences() {
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).edit()
                .remove(BACKUP_DATA_BYTES)
                .remove(QUOTA_BYTES)
                .remove(QUOTA_RESET_TIME)
                .apply();
    }

    private static long getMessageDate(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndex(Telephony.Sms.DATE));
    }

    @Override
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        SharedPreferences sharedPreferences = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE);
        if (sharedPreferences.contains(BACKUP_DATA_BYTES)
                && sharedPreferences.contains(QUOTA_BYTES)) {
            // Increase backup size by the size we skipped during previous backup.
            backupDataBytes += (sharedPreferences.getLong(BACKUP_DATA_BYTES, 0)
                    - sharedPreferences.getLong(QUOTA_BYTES, 0)) * BYTES_OVER_QUOTA_MULTIPLIER;
        }
        sharedPreferences.edit()
                .putLong(BACKUP_DATA_BYTES, backupDataBytes)
                .putLong(QUOTA_BYTES, quotaBytes)
                .putLong(QUOTA_RESET_TIME, System.currentTimeMillis() + QUOTA_RESET_INTERVAL)
                .apply();
    }

    private void backupAll(FullBackupDataOutput data, Cursor cursor, String fileName)
            throws IOException {
        if (cursor == null || cursor.isAfterLast()) {
            return;
        }

        int messagesWritten = 0;
        try (JsonWriter jsonWriter = getJsonWriter(fileName)) {
            if (fileName.endsWith(SMS_BACKUP_FILE_SUFFIX)) {
                messagesWritten = putSmsMessagesToJson(cursor, jsonWriter);
            } else {
                messagesWritten = putMmsMessagesToJson(cursor, jsonWriter);
            }
        }
        backupFile(messagesWritten, fileName, data);
    }

    @VisibleForTesting
    int putMmsMessagesToJson(Cursor cursor,
                             JsonWriter jsonWriter) throws IOException {
        jsonWriter.beginArray();
        int msgCount;
        for (msgCount = 0; msgCount < mMaxMsgPerFile && !cursor.isAfterLast();
                cursor.moveToNext()) {
            msgCount += writeMmsToWriter(jsonWriter, cursor);
        }
        jsonWriter.endArray();
        return msgCount;
    }

    @VisibleForTesting
    int putSmsMessagesToJson(Cursor cursor, JsonWriter jsonWriter) throws IOException {

        jsonWriter.beginArray();
        int msgCount;
        for (msgCount = 0; msgCount < mMaxMsgPerFile && !cursor.isAfterLast();
                ++msgCount, cursor.moveToNext()) {
            writeSmsToWriter(jsonWriter, cursor);
        }
        jsonWriter.endArray();
        return msgCount;
    }

    private void backupFile(int messagesWritten, String fileName, FullBackupDataOutput data)
            throws IOException {
        final File file = new File(getFilesDir().getPath() + "/" + fileName);
        try {
            if (messagesWritten > 0) {
                if (mBytesOverQuota > 0) {
                    mBytesOverQuota -= file.length();
                    return;
                }
                super.fullBackupFile(file, data);
            }
        } finally {
            file.delete();
        }
    }

    public static class DeferredSmsMmsRestoreService extends IntentService {
        private static final String TAG = "DeferredSmsMmsRestoreService";

        private final Comparator<File> mFileComparator = new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                return rhs.getName().compareTo(lhs.getName());
            }
        };

        public DeferredSmsMmsRestoreService() {
            super(TAG);
            setIntentRedelivery(true);
        }

        private TelephonyBackupAgent mTelephonyBackupAgent;

        @Override
        protected void onHandleIntent(Intent intent) {
            File[] files = getFilesDir().listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    return file.getName().endsWith(SMS_BACKUP_FILE_SUFFIX) ||
                            file.getName().endsWith(MMS_BACKUP_FILE_SUFFIX);
                }
            });

            if (files == null) {
                return;
            }
            Arrays.sort(files, mFileComparator);

            for (File file : files) {
                final String fileName = file.getName();
                try (FileInputStream fileInputStream = new FileInputStream(file)) {
                    mTelephonyBackupAgent.doRestoreFile(fileName, fileInputStream.getFD());
                    file.delete();
                } catch (IOException e) {
                    if (DEBUG) {
                        Log.e(TAG, e.toString());
                    }
                }
            }
        }

        @Override
        public void onCreate() {
            super.onCreate();
            mTelephonyBackupAgent = new TelephonyBackupAgent();
            mTelephonyBackupAgent.attach(this);
            mTelephonyBackupAgent.onCreate();
        }

        @Override
        public void onDestroy() {
            if (mTelephonyBackupAgent != null) {
                mTelephonyBackupAgent.onDestroy();
                mTelephonyBackupAgent = null;
            }
            super.onDestroy();
        }

        public static Intent getIntent(Context context) {
            return new Intent(context, DeferredSmsMmsRestoreService.class);
        }
    }

    @Override
    public void onRestoreFinished() {
        super.onRestoreFinished();
        startService(DeferredSmsMmsRestoreService.getIntent(this));
    }

    private void doRestoreFile(String fileName, FileDescriptor fd) throws IOException {
        if (DEBUG) {
            Log.i(TAG, "Restoring file " + fileName);
        }

        try (JsonReader jsonReader = getJsonReader(fd)) {
            if (fileName.endsWith(SMS_BACKUP_FILE_SUFFIX)) {
                if (DEBUG) {
                    Log.i(TAG, "Restoring SMS");
                }
                putSmsMessagesToProvider(jsonReader);
            } else if (fileName.endsWith(MMS_BACKUP_FILE_SUFFIX)) {
                if (DEBUG) {
                    Log.i(TAG, "Restoring text MMS");
                }
                putMmsMessagesToProvider(jsonReader);
            } else {
                if (DEBUG) {
                    Log.e(TAG, "Unknown file to restore:" + fileName);
                }
            }
        }
    }

    @VisibleForTesting
    void putSmsMessagesToProvider(JsonReader jsonReader) throws IOException {
        jsonReader.beginArray();
        int msgCount = 0;
        final int bulkInsertSize = mMaxMsgPerFile;
        ContentValues[] values = new ContentValues[bulkInsertSize];
        while (jsonReader.hasNext()) {
            ContentValues cv = readSmsValuesFromReader(jsonReader);
            if (doesSmsExist(cv)) {
                continue;
            }
            values[(msgCount++) % bulkInsertSize] = cv;
            if (msgCount % bulkInsertSize == 0) {
                mContentResolver.bulkInsert(Telephony.Sms.CONTENT_URI, values);
            }
        }
        if (msgCount % bulkInsertSize > 0) {
            mContentResolver.bulkInsert(Telephony.Sms.CONTENT_URI,
                    Arrays.copyOf(values, msgCount % bulkInsertSize));
        }
        jsonReader.endArray();
    }

    @VisibleForTesting
    void putMmsMessagesToProvider(JsonReader jsonReader) throws IOException {
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            final Mms mms = readMmsFromReader(jsonReader);
            if (doesMmsExist(mms)) {
                if (DEBUG) {
                    Log.e(TAG, String.format("Mms: %s already exists", mms.toString()));
                }
                continue;
            }
            addMmsMessage(mms);
        }
    }

    @VisibleForTesting
    static final String[] PROJECTION_ID = {BaseColumns._ID};
    private static final int ID_IDX = 0;

    private boolean doesSmsExist(ContentValues smsValues) {
        final String where = String.format("%s = %d and %s = %s",
                Telephony.Sms.DATE, smsValues.getAsLong(Telephony.Sms.DATE),
                Telephony.Sms.BODY,
                DatabaseUtils.sqlEscapeString(smsValues.getAsString(Telephony.Sms.BODY)));
        try (Cursor cursor = mContentResolver.query(Telephony.Sms.CONTENT_URI, PROJECTION_ID, where,
                null, null)) {
            return cursor != null && cursor.getCount() > 0;
        }
    }

    private boolean doesMmsExist(Mms mms) {
        final String where = String.format("%s = %d",
                Telephony.Sms.DATE, mms.values.getAsLong(Telephony.Mms.DATE));
        try (Cursor cursor = mContentResolver.query(Telephony.Mms.CONTENT_URI, PROJECTION_ID, where,
                null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    final int mmsId = cursor.getInt(ID_IDX);
                    final MmsBody body = getMmsBody(mmsId);
                    if (body != null && body.equals(mms.body)) {
                        return true;
                    }
                } while (cursor.moveToNext());
            }
        }
        return false;
    }

    private static String getNormalizedNumber(SubscriptionInfo subscriptionInfo) {
        if (subscriptionInfo == null) {
            return null;
        }
        return PhoneNumberUtils.formatNumberToE164(subscriptionInfo.getNumber(),
                subscriptionInfo.getCountryIso().toUpperCase(Locale.US));
    }

    private void writeSmsToWriter(JsonWriter jsonWriter, Cursor cursor) throws IOException {
        jsonWriter.beginObject();

        for (int i=0; i<cursor.getColumnCount(); ++i) {
            final String name = cursor.getColumnName(i);
            final String value = cursor.getString(i);
            if (value == null) {
                continue;
            }
            switch (name) {
                case Telephony.Sms.SUBSCRIPTION_ID:
                    final int subId = cursor.getInt(i);
                    final String selfNumber = mSubId2phone.get(subId);
                    if (selfNumber != null) {
                        jsonWriter.name(SELF_PHONE_KEY).value(selfNumber);
                    }
                    break;
                case Telephony.Sms.THREAD_ID:
                    final long threadId = cursor.getLong(i);
                    writeRecipientsToWriter(jsonWriter.name(RECIPIENTS),
                            getRecipientsByThread(threadId));
                    break;
                case Telephony.Sms._ID:
                    break;
                default:
                    jsonWriter.name(name).value(value);
                    break;
            }
        }
        jsonWriter.endObject();

    }

    private static void writeRecipientsToWriter(JsonWriter jsonWriter, List<String> recipients)
            throws IOException {
        jsonWriter.beginArray();
        if (recipients != null) {
            for (String s : recipients) {
                jsonWriter.value(s);
            }
        }
        jsonWriter.endArray();
    }

    private ContentValues readSmsValuesFromReader(JsonReader jsonReader)
            throws IOException {
        ContentValues values = new ContentValues(8+sDefaultValuesSms.size());
        values.putAll(sDefaultValuesSms);
        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            switch (name) {
                case Telephony.Sms.BODY:
                case Telephony.Sms.DATE:
                case Telephony.Sms.DATE_SENT:
                case Telephony.Sms.STATUS:
                case Telephony.Sms.TYPE:
                case Telephony.Sms.SUBJECT:
                case Telephony.Sms.ADDRESS:
                    values.put(name, jsonReader.nextString());
                    break;
                case RECIPIENTS:
                    values.put(Telephony.Sms.THREAD_ID,
                            getOrCreateThreadId(getRecipients(jsonReader)));
                    break;
                case SELF_PHONE_KEY:
                    final String selfPhone = jsonReader.nextString();
                    if (mPhone2subId.containsKey(selfPhone)) {
                        values.put(Telephony.Sms.SUBSCRIPTION_ID, mPhone2subId.get(selfPhone));
                    }
                    break;
                default:
                    if (DEBUG) {
                        Log.w(TAG, "Unknown name:" + name);
                    }
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();
        return values;
    }

    private static Set<String> getRecipients(JsonReader jsonReader) throws IOException {
        Set<String> recipients = new ArraySet<String>();
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            recipients.add(jsonReader.nextString());
        }
        jsonReader.endArray();
        return recipients;
    }

    private int writeMmsToWriter(JsonWriter jsonWriter, Cursor cursor) throws IOException {
        final int mmsId = cursor.getInt(ID_IDX);
        final MmsBody body = getMmsBody(mmsId);
        if (body == null || body.text == null) {
            return 0;
        }

        boolean subjectNull = true;
        jsonWriter.beginObject();
        for (int i=0; i<cursor.getColumnCount(); ++i) {
            final String name = cursor.getColumnName(i);
            final String value = cursor.getString(i);
            if (value == null) {
                continue;
            }
            switch (name) {
                case Telephony.Mms.SUBSCRIPTION_ID:
                    final int subId = cursor.getInt(i);
                    final String selfNumber = mSubId2phone.get(subId);
                    if (selfNumber != null) {
                        jsonWriter.name(SELF_PHONE_KEY).value(selfNumber);
                    }
                    break;
                case Telephony.Mms.THREAD_ID:
                    final long threadId = cursor.getLong(i);
                    writeRecipientsToWriter(jsonWriter.name(RECIPIENTS),
                            getRecipientsByThread(threadId));
                    break;
                case Telephony.Mms._ID:
                case Telephony.Mms.SUBJECT_CHARSET:
                    break;
                case Telephony.Mms.SUBJECT:
                    subjectNull = false;
                default:
                    jsonWriter.name(name).value(value);
                    break;
            }
        }
        // Addresses.
        writeMmsAddresses(jsonWriter.name(MMS_ADDRESSES_KEY), mmsId);
        // Body (text of the message).
        jsonWriter.name(MMS_BODY_KEY).value(body.text);
        // Charset of the body text.
        jsonWriter.name(MMS_BODY_CHARSET_KEY).value(body.charSet);

        if (!subjectNull) {
            // Subject charset.
            writeStringToWriter(jsonWriter, cursor, Telephony.Mms.SUBJECT_CHARSET);
        }
        jsonWriter.endObject();
        return 1;
    }

    private Mms readMmsFromReader(JsonReader jsonReader) throws IOException {
        Mms mms = new Mms();
        mms.values = new ContentValues(6+sDefaultValuesMms.size());
        mms.values.putAll(sDefaultValuesMms);
        jsonReader.beginObject();
        String bodyText = null;
        int bodyCharset = CharacterSets.DEFAULT_CHARSET;
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            switch (name) {
                case SELF_PHONE_KEY:
                    final String selfPhone = jsonReader.nextString();
                    if (mPhone2subId.containsKey(selfPhone)) {
                        mms.values.put(Telephony.Mms.SUBSCRIPTION_ID, mPhone2subId.get(selfPhone));
                    }
                    break;
                case MMS_ADDRESSES_KEY:
                    getMmsAddressesFromReader(jsonReader, mms);
                    break;
                case MMS_BODY_KEY:
                    bodyText = jsonReader.nextString();
                    break;
                case MMS_BODY_CHARSET_KEY:
                    bodyCharset = jsonReader.nextInt();
                    break;
                case RECIPIENTS:
                    mms.values.put(Telephony.Sms.THREAD_ID,
                            getOrCreateThreadId(getRecipients(jsonReader)));
                    break;
                case Telephony.Mms.SUBJECT:
                case Telephony.Mms.SUBJECT_CHARSET:
                case Telephony.Mms.DATE:
                case Telephony.Mms.DATE_SENT:
                case Telephony.Mms.MESSAGE_TYPE:
                case Telephony.Mms.MMS_VERSION:
                case Telephony.Mms.MESSAGE_BOX:
                case Telephony.Mms.CONTENT_LOCATION:
                    mms.values.put(name, jsonReader.nextString());
                    break;
                default:
                    if (DEBUG) {
                        Log.w(TAG, "Unknown name:" + name);
                    }
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();

        if (bodyText != null) {
            mms.body = new MmsBody(bodyText, bodyCharset);
        }

        // Set default charset for subject.
        if (mms.values.get(Telephony.Mms.SUBJECT) != null &&
                mms.values.get(Telephony.Mms.SUBJECT_CHARSET) == null) {
            mms.values.put(Telephony.Mms.SUBJECT_CHARSET, CharacterSets.DEFAULT_CHARSET);
        }

        return mms;
    }

    private MmsBody getMmsBody(int mmsId) {
        Uri MMS_PART_CONTENT_URI = Telephony.Mms.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(mmsId)).appendPath("part").build();

        String body = null;
        int charSet = 0;

        try (Cursor cursor = mContentResolver.query(MMS_PART_CONTENT_URI, MMS_TEXT_PROJECTION,
                Telephony.Mms.Part.CONTENT_TYPE + "=?", new String[]{ContentType.TEXT_PLAIN},
                ORDER_BY_ID)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    body = (body == null ? cursor.getString(MMS_TEXT_IDX)
                            : body.concat(cursor.getString(MMS_TEXT_IDX)));
                    charSet = cursor.getInt(MMS_TEXT_CHARSET_IDX);
                } while (cursor.moveToNext());
            }
        }
        return (body == null ? null : new MmsBody(body, charSet));
    }

    private void writeMmsAddresses(JsonWriter jsonWriter, int mmsId) throws IOException {
        Uri.Builder builder = Telephony.Mms.CONTENT_URI.buildUpon();
        builder.appendPath(String.valueOf(mmsId)).appendPath("addr");
        Uri uriAddrPart = builder.build();

        jsonWriter.beginArray();
        try (Cursor cursor = mContentResolver.query(uriAddrPart, MMS_ADDR_PROJECTION,
                null/*selection*/, null/*selectionArgs*/, ORDER_BY_ID)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (cursor.getString(cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS))
                            != null) {
                        jsonWriter.beginObject();
                        writeIntToWriter(jsonWriter, cursor, Telephony.Mms.Addr.TYPE);
                        writeStringToWriter(jsonWriter, cursor, Telephony.Mms.Addr.ADDRESS);
                        writeIntToWriter(jsonWriter, cursor, Telephony.Mms.Addr.CHARSET);
                        jsonWriter.endObject();
                    }
                } while (cursor.moveToNext());
            }
        }
        jsonWriter.endArray();
    }

    private static void getMmsAddressesFromReader(JsonReader jsonReader, Mms mms)
            throws IOException {
        mms.addresses = new ArrayList<ContentValues>();
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            jsonReader.beginObject();
            ContentValues addrValues = new ContentValues(sDefaultValuesAddr);
            while (jsonReader.hasNext()) {
                final String name = jsonReader.nextName();
                switch (name) {
                    case Telephony.Mms.Addr.TYPE:
                    case Telephony.Mms.Addr.CHARSET:
                        addrValues.put(name, jsonReader.nextInt());
                        break;
                    case Telephony.Mms.Addr.ADDRESS:
                        addrValues.put(name, jsonReader.nextString());
                        break;
                    default:
                        if (DEBUG) {
                            Log.w(TAG, "Unknown name:" + name);
                        }
                        jsonReader.skipValue();
                        break;
                }
            }
            jsonReader.endObject();
            if (addrValues.containsKey(Telephony.Mms.Addr.ADDRESS)) {
                mms.addresses.add(addrValues);
            }
        }
        jsonReader.endArray();
    }

    private void addMmsMessage(Mms mms) {
        if (DEBUG) {
            Log.e(TAG, "Add mms:\n" + mms.toString());
        }
        final long dummyId = System.currentTimeMillis(); // Dummy ID of the msg.
        final Uri partUri = Telephony.Mms.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(dummyId)).appendPath("part").build();

        final String srcName = String.format("text.%06d.txt", 0);
        { // Insert SMIL part.
            final String smilBody = String.format(sSmilTextPart, srcName);
            final String smil = String.format(sSmilTextOnly, smilBody);
            final ContentValues values = new ContentValues(7);
            values.put(Telephony.Mms.Part.MSG_ID, dummyId);
            values.put(Telephony.Mms.Part.SEQ, -1);
            values.put(Telephony.Mms.Part.CONTENT_TYPE, ContentType.APP_SMIL);
            values.put(Telephony.Mms.Part.NAME, "smil.xml");
            values.put(Telephony.Mms.Part.CONTENT_ID, "<smil>");
            values.put(Telephony.Mms.Part.CONTENT_LOCATION, "smil.xml");
            values.put(Telephony.Mms.Part.TEXT, smil);
            if (mContentResolver.insert(partUri, values) == null) {
                if (DEBUG) {
                    Log.e(TAG, "Could not insert SMIL part");
                }
                return;
            }
        }

        { // Insert body part.
            final ContentValues values = new ContentValues(8);
            values.put(Telephony.Mms.Part.MSG_ID, dummyId);
            values.put(Telephony.Mms.Part.SEQ, 0);
            values.put(Telephony.Mms.Part.CONTENT_TYPE, ContentType.TEXT_PLAIN);
            values.put(Telephony.Mms.Part.NAME, srcName);
            values.put(Telephony.Mms.Part.CONTENT_ID, "<"+srcName+">");
            values.put(Telephony.Mms.Part.CONTENT_LOCATION, srcName);
            values.put(Telephony.Mms.Part.CHARSET, mms.body.charSet);
            values.put(Telephony.Mms.Part.TEXT, mms.body.text);
            if (mContentResolver.insert(partUri, values) == null) {
                if (DEBUG) {
                    Log.e(TAG, "Could not insert body part");
                }
                return;
            }
        }

        // Insert mms.
        final Uri mmsUri = mContentResolver.insert(Telephony.Mms.CONTENT_URI, mms.values);
        if (mmsUri == null) {
            if (DEBUG) {
                Log.e(TAG, "Could not insert mms");
            }
            return;
        }

        final long mmsId = ContentUris.parseId(mmsUri);
        { // Update parts with the right mms id.
            ContentValues values = new ContentValues(1);
            values.put(Telephony.Mms.Part.MSG_ID, mmsId);
            mContentResolver.update(partUri, values, null, null);
        }

        { // Insert adderesses into "addr".
            final Uri addrUri = Uri.withAppendedPath(mmsUri, "addr");
            for (ContentValues mmsAddress : mms.addresses) {
                ContentValues values = new ContentValues(mmsAddress);
                values.put(Telephony.Mms.Addr.MSG_ID, mmsId);
                mContentResolver.insert(addrUri, values);
            }
        }
    }

    private static final class MmsBody {
        public String text;
        public int charSet;

        public MmsBody(String text, int charSet) {
            this.text = text;
            this.charSet = charSet;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof MmsBody)) {
                return false;
            }
            MmsBody typedObj = (MmsBody) obj;
            return this.text.equals(typedObj.text) && this.charSet == typedObj.charSet;
        }

        @Override
        public String toString() {
            return "Text:" + text + " charSet:" + charSet;
        }
    }

    private static final class Mms {
        public ContentValues values;
        public List<ContentValues> addresses;
        public MmsBody body;
        @Override
        public String toString() {
            return "Values:" + values.toString() + "\nRecipients:"+addresses.toString()
                    + "\nBody:" + body;
        }
    }

    private JsonWriter getJsonWriter(final String fileName) throws IOException {
        return new JsonWriter(new BufferedWriter(new OutputStreamWriter(new DeflaterOutputStream(
                openFileOutput(fileName, MODE_PRIVATE)), CHARSET_UTF8), WRITER_BUFFER_SIZE));
    }

    private static JsonReader getJsonReader(final FileDescriptor fileDescriptor)
            throws IOException {
        return new JsonReader(new InputStreamReader(new InflaterInputStream(
                new FileInputStream(fileDescriptor)), CHARSET_UTF8));
    }

    private static void writeStringToWriter(JsonWriter jsonWriter, Cursor cursor, String name)
            throws IOException {
        final String value = cursor.getString(cursor.getColumnIndex(name));
        if (value != null) {
            jsonWriter.name(name).value(value);
        }
    }

    private static void writeIntToWriter(JsonWriter jsonWriter, Cursor cursor, String name)
            throws IOException {
        final int value = cursor.getInt(cursor.getColumnIndex(name));
        if (value != 0) {
            jsonWriter.name(name).value(value);
        }
    }

    private long getOrCreateThreadId(Set<String> recipients) {
        if (mCacheGetOrCreateThreadId == null) {
            mCacheGetOrCreateThreadId = new HashMap<>();
        }

        if (!mCacheGetOrCreateThreadId.containsKey(recipients)) {
            mCacheGetOrCreateThreadId.put(recipients,
                    Telephony.Threads.getOrCreateThreadId(this, recipients));
        }

        return mCacheGetOrCreateThreadId.get(recipients);
    }

    @VisibleForTesting
    static final Uri THREAD_ID_CONTENT_URI = Uri.parse("content://mms-sms/threadID");

    // Mostly copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private List<String> getRecipientsByThread(final long threadId) {
        if (mCacheRecipientsByThread == null) {
            mCacheRecipientsByThread = new HashMap<>();
        }

        if (!mCacheRecipientsByThread.containsKey(threadId)) {
            final String spaceSepIds = getRawRecipientIdsForThread(threadId);
            if (!TextUtils.isEmpty(spaceSepIds)) {
                mCacheRecipientsByThread.put(threadId, getAddresses(spaceSepIds));
            } else {
                mCacheRecipientsByThread.put(threadId, new ArrayList<String>());
            }
        }

        return mCacheRecipientsByThread.get(threadId);
    }

    @VisibleForTesting
    static final Uri ALL_THREADS_URI =
            Telephony.Threads.CONTENT_URI.buildUpon().
                    appendQueryParameter("simple", "true").build();
    private static final int RECIPIENT_IDS  = 1;

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    // NOTE: There are phones on which you can't get the recipients from the thread id for SMS
    // until you have a message in the conversation!
    private String getRawRecipientIdsForThread(final long threadId) {
        if (threadId <= 0) {
            return null;
        }
        final Cursor thread = mContentResolver.query(
                ALL_THREADS_URI,
                SMS_RECIPIENTS_PROJECTION, "_id=?", new String[]{String.valueOf(threadId)}, null);
        if (thread != null) {
            try {
                if (thread.moveToFirst()) {
                    // recipientIds will be a space-separated list of ids into the
                    // canonical addresses table.
                    return thread.getString(RECIPIENT_IDS);
                }
            } finally {
                thread.close();
            }
        }
        return null;
    }

    @VisibleForTesting
    static final Uri SINGLE_CANONICAL_ADDRESS_URI =
            Uri.parse("content://mms-sms/canonical-address");

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private List<String> getAddresses(final String spaceSepIds) {
        final List<String> numbers = new ArrayList<String>();
        final String[] ids = spaceSepIds.split(" ");
        for (final String id : ids) {
            long longId;

            try {
                longId = Long.parseLong(id);
                if (longId < 0) {
                    if (DEBUG) {
                        Log.e(TAG, "getAddresses: invalid id " + longId);
                    }
                    continue;
                }
            } catch (final NumberFormatException ex) {
                if (DEBUG) {
                    Log.e(TAG, "getAddresses: invalid id. " + ex, ex);
                }
                // skip this id
                continue;
            }

            // TODO: build a single query where we get all the addresses at once.
            Cursor c = null;
            try {
                c = mContentResolver.query(
                        ContentUris.withAppendedId(SINGLE_CANONICAL_ADDRESS_URI, longId),
                        null, null, null, null);
            } catch (final Exception e) {
                if (DEBUG) {
                    Log.e(TAG, "getAddresses: query failed for id " + longId, e);
                }
            }
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        final String number = c.getString(0);
                        if (!TextUtils.isEmpty(number)) {
                            numbers.add(number);
                        } else {
                            if (DEBUG) {
                                Log.w(TAG, "Canonical MMS/SMS address is empty for id: " + longId);
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        if (numbers.isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "No MMS addresses found from ids string [" + spaceSepIds + "]");
            }
        }
        return numbers;
    }

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
                         ParcelFileDescriptor newState) throws IOException {
        // Empty because is not used during full backup.
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
                          ParcelFileDescriptor newState) throws IOException {
        // Empty because is not used during full restore.
    }
}