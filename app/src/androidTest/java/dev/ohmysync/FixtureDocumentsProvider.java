package dev.ohmysync;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.UUID;

/** 仅存在于测试 APK，使用平台 Java 类，文件全部位于测试应用私有目录。 */
public class FixtureDocumentsProvider extends DocumentsProvider {
    private File base() {
        File folder = new File(getContext().getFilesDir(), "曲库测试");
        folder.mkdirs();
        return folder;
    }
    private File file(String id) {
        File result = new File(base(), id);
        try {
            if (!result.getCanonicalPath().startsWith(base().getCanonicalPath() + File.separator)) throw new IllegalStateException("测试路径越界");
        } catch (IOException error) { throw new IllegalStateException(error); }
        return result;
    }
    @Override public boolean onCreate() { return true; }
    @Override public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
    @Override public Bundle call(String authority, String method, String arg, Bundle extras) {
        if ("newRoot".equals(method)) {
            String id = UUID.randomUUID().toString();
            if (!file(id).mkdir()) throw new IllegalStateException("无法创建测试目录");
            getContext().grantUriPermission("dev.ohmysync", DocumentsContract.buildTreeDocumentUri(authority, id),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            Bundle result = new Bundle(); result.putString("id", id); return result;
        }
        return super.call(authority, method, arg, extras);
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[]{"root_id"} : projection); }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED} : projection);
    }
    private void add(MatrixCursor cursor, String id) {
        File f = file(id);
        if (!f.exists()) throw new IllegalStateException("测试文件不存在");
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (Document.COLUMN_DOCUMENT_ID.equals(column)) value = id;
            if (Document.COLUMN_DISPLAY_NAME.equals(column)) value = f.getName();
            if (Document.COLUMN_MIME_TYPE.equals(column)) value = f.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream";
            if (Document.COLUMN_SIZE.equals(column)) value = f.length();
            if (Document.COLUMN_LAST_MODIFIED.equals(column)) value = f.lastModified();
            if (Document.COLUMN_FLAGS.equals(column)) value = Document.FLAG_SUPPORTS_RENAME | (f.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);
            row.add(value);
        }
    }
    @Override public Cursor queryDocument(String id, String[] projection) { MatrixCursor c = cursor(projection); add(c, id); return c; }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        MatrixCursor c = cursor(projection);
        File[] children = file(parent).listFiles();
        if (children == null) throw new IllegalStateException("无法完整枚举测试目录");
        for (File child : children) add(c, parent + "/" + child.getName());
        return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent, String mimeType, String name) throws FileNotFoundException {
        if (name.contains("/") || "..".equals(name)) throw new IllegalArgumentException("测试文件名无效");
        String id = parent + "/" + name;
        try {
            boolean created = Document.MIME_TYPE_DIR.equals(mimeType) ? file(id).mkdir() : file(id).createNewFile();
            if (!created) throw new IllegalStateException("测试名称已存在");
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        return id;
    }
    @Override public String renameDocument(String id, String name) {
        if (name.contains("/") || "..".equals(name)) throw new IllegalArgumentException("测试文件名无效");
        String next = id.substring(0, id.lastIndexOf('/')) + "/" + name;
        if (file(next).exists() || !file(id).renameTo(file(next))) throw new IllegalStateException("测试改名失败");
        return next;
    }
    @Override public void deleteDocument(String id) { throw new UnsupportedOperationException("测试提供方不执行永久删除"); }
}
