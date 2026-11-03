package dev.findroid.qa;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate read-only synthetic provider for optimized-APK acceptance; never in Findroid. */
public final class TransferDocumentsProvider extends DocumentsProvider {
    public static final long SIZE = 8L * 1024 * 1024;
    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "transfer-tests")
            .add(Root.COLUMN_DOCUMENT_ID, "root").add(Root.COLUMN_TITLE, "Transfer test files")
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_MIME_TYPES, "application/octet-stream")
            .add(Root.COLUMN_AVAILABLE_BYTES, 0L);
        return cursor;
    }

    private void document(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean folder = "root".equals(id);
        if (!folder && !"known".equals(id) && !"unknown".equals(id))
            throw new FileNotFoundException("Only synthetic test documents are available");
        Object size = null;
        if (folder) size = 0L;
        else if ("known".equals(id)) size = SIZE;
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, folder ? "Transfer test files" :
                ("known".equals(id) ? "Transfer sample.bin" : "Unknown size.bin"))
            .add(Document.COLUMN_MIME_TYPE, folder ? Document.MIME_TYPE_DIR : "application/octet-stream")
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, size)
            .add(Document.COLUMN_LAST_MODIFIED, 1700000000000L);
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        document(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order)
            throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException("No synthetic child folder");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        document(cursor, "known");
        document(cursor, "unknown");
        return cursor;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        return "root".equals(parent) && ("known".equals(child) || "unknown".equals(child));
    }

    private Properties configuration() {
        Properties value = new Properties();
        try (FileInputStream input = new FileInputStream(new File(getContext().getFilesDir(), "control.properties"))) {
            value.load(input);
        } catch (IOException ignored) { }
        return value;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode) || (!"known".equals(id) && !"unknown".equals(id)))
            throw new FileNotFoundException("This synthetic provider is read only");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException error) { throw new FileNotFoundException("Could not create synthetic pipe"); }
        AtomicBoolean cancelled = new AtomicBoolean();
        if (signal != null) signal.setOnCancelListener(new CancellationSignal.OnCancelListener() {
            @Override public void onCancel() {
            cancelled.set(true);
            for (ParcelFileDescriptor descriptor : pipe) {
                try { descriptor.close(); } catch (IOException ignored) { }
            }
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                Properties config = configuration();
                String session = config.getProperty("session", "default");
                if (!session.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("Invalid test session");
                try (OutputStream receipt = new FileOutputStream(new File(getContext().getFilesDir(), "opened-" + session + "-" + id))) {
                    receipt.write("Synthetic stream opened\n".getBytes("UTF-8"));
                }
                long deadline = SystemClock.elapsedRealtime() + 120000;
                while ("closed".equals(configuration().getProperty("gate"))) {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline)
                        throw new IOException("Synthetic gate was cancelled or timed out");
                    Thread.sleep(100);
                }
                int delay = Integer.parseInt(config.getProperty("delayMillis", "100"));
                if (delay < 0 || delay > 500) throw new IOException("Invalid synthetic delay");
                byte[] buffer = new byte[64 * 1024];
                for (long offset = 0; offset < SIZE; offset += buffer.length) {
                    if (cancelled.get()) break;
                    for (int index = 0; index < buffer.length; index++)
                        buffer[index] = (byte) ((offset + index) % 251);
                    output.write(buffer);
                    if (delay > 0) Thread.sleep(delay);
                }
            } catch (Exception ignored) {
                // Cancellation/broken pipes are expected. Never export client data or logs.
            }
            }
        }, "synthetic-transfer-provider").start();
        return pipe[0];
    }
}
