package dev.findroid.qa;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate deterministic source and owned writable target for optimized-APK acceptance. */
public final class VerificationDocumentsProvider extends DocumentsProvider {
    public static final long SIZE = 8L * 1024 * 1024;
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    private File destination() { File f = new File(getContext().getFilesDir(), "destination"); f.mkdirs(); return f; }
    @Override public boolean onCreate() { destination(); return true; }
    private File target(String id) throws FileNotFoundException {
        if (!id.equals("out") && !id.startsWith("out/")) throw new FileNotFoundException("Not an owned destination");
        try {
            File root = destination().getCanonicalFile();
            File file = id.equals("out") ? root : new File(root, id.substring(4)).getCanonicalFile();
            if (!file.toPath().startsWith(root.toPath())) throw new IOException("Outside owned destination");
            return file;
        } catch (IOException error) { throw new FileNotFoundException("Invalid synthetic target"); }
    }
    private String id(File file) throws IOException {
        return "out" + (file.getCanonicalFile().equals(destination().getCanonicalFile()) ? "" :
            "/" + destination().getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString());
    }
    private boolean folder(String id) { return id.equals("root") || id.equals("bundle") || id.equals("bundle/Sub") || id.equals("bundle/Empty"); }
    private long sourceSize(String id) throws FileNotFoundException {
        if (id.equals("known") || id.equals("unknown")) return SIZE;
        if (id.equals("bundle/One.bin")) return 1024L * 1024;
        if (id.equals("bundle/Sub/Two.bin")) return 2L * 1024 * 1024;
        throw new FileNotFoundException("Unknown synthetic source");
    }
    private String sourceName(String id) {
        if (id.equals("root")) return "Verification test files";
        if (id.equals("known")) return "Transfer sample.bin";
        if (id.equals("unknown")) return "Unknown size.bin";
        if (id.equals("bundle")) return "Nested originals";
        return id.substring(id.lastIndexOf('/') + 1);
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        c.newRow().add(Root.COLUMN_ROOT_ID,"verification-tests").add(Root.COLUMN_DOCUMENT_ID,"root")
            .add(Root.COLUMN_TITLE,"Verification test files").add(Root.COLUMN_FLAGS,Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_MIME_TYPES,"*/*").add(Root.COLUMN_AVAILABLE_BYTES,512L * 1024 * 1024);
        return c;
    }
    private void document(MatrixCursor c, String id) throws FileNotFoundException {
        boolean writable = id.equals("out") || id.startsWith("out/");
        File file = writable ? target(id) : null;
        if (file != null && !file.exists()) throw new FileNotFoundException("Missing synthetic target");
        boolean directory = writable ? file.isDirectory() : folder(id);
        Object size = null;
        if (directory) size = 0L;
        else if (!id.equals("unknown")) size = writable ? file.length() : sourceSize(id);
        int flags = writable ? (directory ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE) : 0;
        if (writable && !id.equals("out")) flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        c.newRow().add(Document.COLUMN_DOCUMENT_ID,id)
            .add(Document.COLUMN_DISPLAY_NAME,id.equals("out") ? "Provider destination" : writable ? file.getName() : sourceName(id))
            .add(Document.COLUMN_MIME_TYPE,directory ? Document.MIME_TYPE_DIR : "application/octet-stream")
            .add(Document.COLUMN_FLAGS,flags).add(Document.COLUMN_SIZE,size)
            .add(Document.COLUMN_LAST_MODIFIED,writable ? file.lastModified() : 1700000000000L);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection); document(c,id); return c;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        if (parent.equals("root")) for (String id : new String[]{"known","unknown","bundle","out"}) document(c,id);
        else if (parent.equals("bundle")) for (String id : new String[]{"bundle/One.bin","bundle/Sub","bundle/Empty"}) document(c,id);
        else if (parent.equals("bundle/Sub")) document(c,"bundle/Sub/Two.bin");
        else if (parent.equals("bundle/Empty")) { }
        else {
            File folder = target(parent); File[] children = folder.listFiles();
            if (children == null) throw new FileNotFoundException("Cannot list synthetic destination");
            for (File child : children) try { document(c,id(child)); } catch (IOException error) { throw new FileNotFoundException("Invalid child"); }
        }
        return c;
    }
    @Override public boolean isChildDocument(String parent, String child) {
        if (parent.equals("root")) return child.equals("known") || child.equals("unknown") || child.equals("bundle") || child.startsWith("bundle/") || child.equals("out") || child.startsWith("out/");
        return (parent.equals("bundle") || parent.equals("bundle/Sub") || parent.equals("out") || parent.startsWith("out/")) && child.startsWith(parent + "/");
    }
    private Properties configuration() {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(new File(getContext().getFilesDir(),"control.properties"))) { p.load(in); }
        catch (IOException ignored) { }
        return p;
    }
    private String session(Properties p) throws IOException {
        String s = p.getProperty("session","default");
        if (!s.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("Invalid synthetic session"); return s;
    }
    private boolean staging(String id) { for (String part : id.split("/")) if (part.startsWith(".findroid-")) return true; return false; }
    private void opened(String phase, String id, Properties p) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(getContext().getFilesDir(),"opened-" + session(p) + "-" + phase))) {
            out.write((id + "\n").getBytes("UTF-8"));
        }
    }
    @Override public ParcelFileDescriptor openDocument(final String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        final boolean writable = id.equals("out") || id.startsWith("out/");
        final File file = writable ? target(id) : null;
        if (!mode.equals("r")) {
            if (!writable || id.equals("out")) throw new FileNotFoundException("Synthetic originals are read only");
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
        }
        final long size = writable ? file.length() : sourceSize(id);
        final boolean checking = writable && staging(id);
        final Properties config = configuration();
        final String phase = checking ? "readback" : "source";
        try {
            opened(phase,id,config);
            if (checking && config.getProperty("readback","normal").equals("unreadable")) throw new IOException("Synthetic readback denied");
            if (checking && config.getProperty("readback","normal").equals("corrupt") && file.length() > 0) {
                try (RandomAccessFile bytes = new RandomAccessFile(file,"rw")) { int first = bytes.read(); bytes.seek(0); bytes.write(first ^ 1); }
            }
            int delay = Integer.parseInt(config.getProperty(phase + "DelayMillis","0"));
            if (delay < 0 || delay > 500) throw new IOException("Invalid synthetic delay");
            if (writable && !checking) return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
            final int pause = delay;
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            final AtomicBoolean cancelled = new AtomicBoolean();
            if (signal != null) signal.setOnCancelListener(new CancellationSignal.OnCancelListener() {
                @Override public void onCancel() { cancelled.set(true); for (ParcelFileDescriptor fd : pipe) try { fd.close(); } catch (IOException ignored) { } }
            });
            new Thread(new Runnable() { @Override public void run() {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    long deadline = SystemClock.elapsedRealtime() + 120000;
                    while (configuration().getProperty(phase + "Gate","open").equals("closed")) {
                        if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) throw new IOException("Synthetic gate expired");
                        Thread.sleep(100);
                    }
                    byte[] buffer = new byte[64 * 1024];
                    try (InputStream in = writable ? new FileInputStream(file) : null) {
                        for (long offset = 0; offset < size && !cancelled.get();) {
                            int count = (int)Math.min(buffer.length,size - offset);
                            if (in != null) {
                                int read = in.read(buffer,0,count); if (read < 0) break; count = read;
                            } else for (int i = 0; i < count; i++) buffer[i] = (byte)((offset + i) % 251);
                            out.write(buffer,0,count); offset += count; if (pause > 0) Thread.sleep(pause);
                        }
                    }
                } catch (Exception ignored) { /* Broken pipes and cancellation are expected; never export client data. */ }
            }},"synthetic-verification-provider").start();
            return pipe[0];
        } catch (Exception error) { throw new FileNotFoundException("Synthetic verification read could not open"); }
    }
    private void name(String value) throws FileNotFoundException {
        if (value == null || value.isEmpty() || value.equals(".") || value.equals("..") || value.contains("/") || value.indexOf(0) >= 0)
            throw new FileNotFoundException("Invalid synthetic name");
    }
    @Override public String createDocument(String parent, String mime, String displayName) throws FileNotFoundException {
        name(displayName); File folder = target(parent); File child = new File(folder,displayName);
        try {
            if (!folder.isDirectory() || child.exists()) throw new IOException("Synthetic target collision");
            if (!(mime.equals(Document.MIME_TYPE_DIR) ? child.mkdir() : child.createNewFile())) throw new IOException("Cannot create synthetic child");
            return id(child);
        } catch (IOException error) { throw new FileNotFoundException("Cannot create synthetic child"); }
    }
    @Override public String renameDocument(String from, String displayName) throws FileNotFoundException {
        name(displayName); if (from.equals("out")) throw new FileNotFoundException("Keep the owned destination root");
        File source = target(from); File to = new File(source.getParentFile(),displayName);
        try { if (to.exists() || !source.renameTo(to)) throw new IOException("Cannot rename synthetic child"); return id(to); }
        catch (IOException error) { throw new FileNotFoundException("Cannot rename synthetic child"); }
    }
    private boolean remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) if (!remove(child)) return false; return file.delete(); }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.equals("out") || !remove(target(id))) throw new FileNotFoundException("Cannot remove synthetic child");
    }
}
