package com.moyue.reader.feature.importbook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Private provider in the sender APK; real temporary grants are required by the reader. */
public class ShareFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private String extension(Uri uri) {
        String name = uri.getLastPathSegment();
        return name == null ? "" : name.substring(name.lastIndexOf('.') + 1);
    }
    @Override public String getType(Uri uri) {
        switch (extension(uri)) {
            case "pdf": return "application/pdf";
            case "epub": return "application/epub+zip";
            case "md": return "text/markdown";
            case "png": return "image/png";
            case "cbz": return "application/vnd.comicbook+zip";
            case "zip": return "application/zip";
            default: return "text/plain";
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{uri.getLastPathSegment()});
        return cursor;
    }
    private void entry(ZipOutputStream zip, String path, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        File file = new File(getContext().getCacheDir(), "share-fixtures/" + uri.getLastPathSegment());
        file.getParentFile().mkdirs();
        String asset = null;
        switch (extension(uri)) {
            case "pdf": asset = "pdf-fixtures/pdf-basic.pdf"; break;
            case "png": asset = "visual-fixtures/image.png"; break;
            case "cbz": asset = "visual-fixtures/comic.cbz"; break;
            case "zip": asset = "visual-fixtures/images.zip"; break;
        }
        try {
            if (asset != null) {
                try (InputStream input = getContext().getAssets().open(asset); FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
            } else if ("epub".equals(extension(uri))) {
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                    entry(zip, "mimetype", "application/epub+zip");
                    entry(zip, "META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
                    entry(zip, "content.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">share-fixture</dc:identifier><dc:title>分享 EPUB 回归</dc:title><dc:language>zh</dc:language></metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>");
                    entry(zip, "chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head><body><h1>第一章 分享</h1><p>这是通过系统分享导入的 EPUB 正文。</p></body></html>");
                }
            } else {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    for (int i = 0; i < 12; i++) output.write("# 分享文件回归\n\n第一章 分享\n这是系统分享的正文。".getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
