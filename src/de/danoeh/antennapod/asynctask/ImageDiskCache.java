package de.danoeh.antennapod.asynctask;

import android.util.Log;
import android.util.Pair;
import android.widget.ImageView;
import de.danoeh.antennapod.AppConfig;
import de.danoeh.antennapod.PodcastApp;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provides local cache for storing downloaded image. An image disk cache downloads images and stores them as long
 * as the cache is not full. Once the cache is full, the image disk cache will delete older images.
 */
public class ImageDiskCache {
    private static final String TAG = "ImageDiskCache";

    private static HashMap<String, ImageDiskCache> cacheSingletons = new HashMap<String, ImageDiskCache>();

    /**
     * Return a default instance of an ImageDiskCache. This cache will store data in the external cache folder.
     */
    public static synchronized ImageDiskCache getDefaultInstance() {
        final String DEFAULT_PATH = "imagecache";
        final long DEFAULT_MAX_CACHE_SIZE = 10 * 1024 * 1024;

        File cacheDir = PodcastApp.getInstance().getExternalCacheDir();
        if (cacheDir == null) {
            return null;
        }
        return getInstance(new File(cacheDir, DEFAULT_PATH).getAbsolutePath(), DEFAULT_MAX_CACHE_SIZE);
    }

    /**
     * Return an instance of an ImageDiskCache that stores images in the specified folder.
     */
    public static synchronized ImageDiskCache getInstance(String path, long maxCacheSize) {
        if (path == null) {
            throw new NullPointerException();
        }
        ImageDiskCache cache = cacheSingletons.get(path);
        if (cache == null) {
            cache = new ImageDiskCache(path, maxCacheSize);
            cacheSingletons.put(new File(path).getAbsolutePath(), cache);
        }
        return cache;
    }

    /**
     * Filename - cache object mapping
     */
    private static final int KEY_IMAGE_DISK_CACHE = 2;
    private static final String CACHE_FILE_NAME = "cachefile";
    private ExecutorService executor;
    private ConcurrentHashMap<String, DiskCacheObject> diskCache;
    private final long maxCacheSize;
    private int cacheSize;
    private final File cacheFolder;

    private ImageDiskCache(String path, long maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
        this.cacheFolder = new File(path);
        if (!cacheFolder.exists() && !cacheFolder.mkdir()) {
            throw new IllegalArgumentException("Image disk cache could not create cache folder in: " + path);
        }

        executor = Executors.newFixedThreadPool(Runtime.getRuntime()
                .availableProcessors());
    }

    private synchronized void initCacheFolder() {
        if (diskCache != null) {
            File cacheFile = new File(cacheFolder, CACHE_FILE_NAME);
            if (cacheFile.exists()) {
                try {
                    InputStream in = new FileInputStream(cacheFile);
                    BufferedInputStream buffer = new BufferedInputStream(in);
                    ObjectInputStream objectInput = new ObjectInputStream(buffer);
                    diskCache = (ConcurrentHashMap<String, DiskCacheObject>) objectInput.readObject();
                    // calculate cache size
                    for (DiskCacheObject dco : diskCache.values()) {
                        cacheSize += dco.size;
                    }
                    deleteInvalidFiles();
                } catch (IOException e) {
                    e.printStackTrace();
                    diskCache = new ConcurrentHashMap<String, DiskCacheObject>();
                } catch (ClassCastException e) {
                    e.printStackTrace();
                    diskCache = new ConcurrentHashMap<String, DiskCacheObject>();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                    diskCache = new ConcurrentHashMap<String, DiskCacheObject>();
                }
            } else {
                diskCache = new ConcurrentHashMap<String, DiskCacheObject>();
            }
        }
    }

    private List<File> getCacheFileList() {
        Collection<DiskCacheObject> values = diskCache.values();
        List<File> files = new ArrayList<File>();
        for (DiskCacheObject dco : values) {
            files.add(dco.getFile());
        }
        files.add(new File(cacheFolder, CACHE_FILE_NAME));
        return files;
    }

    private Pair<String, DiskCacheObject> getOldestCacheObject() {
        Collection<String> keys = diskCache.keySet();
        DiskCacheObject oldest = null;
        String oldestKey = null;

        for (String key : keys) {

            if (oldestKey == null) {
                oldestKey = key;
                oldest = diskCache.get(key);
            } else {
                DiskCacheObject dco = diskCache.get(key);
                if (oldest.timestamp > dco.timestamp) {
                    oldestKey = key;
                    oldest = diskCache.get(key);
                }
            }
        }
        return new Pair<String, DiskCacheObject>(oldestKey, oldest);
    }

    private synchronized void deleteCacheObject(String key, DiskCacheObject value) {
        Log.i(TAG, "Deleting cached object: " + key);
        diskCache.remove(key);
        boolean result = value.getFile().delete();
        if (!result) {
            Log.w(TAG, "Could not delete file " + value.fileUrl);
        }
        cacheSize -= value.size;
    }

    private synchronized void deleteInvalidFiles() {
        // delete files that are not stored inside the cache
        File[] files = cacheFolder.listFiles();
        List<File> cacheFiles = getCacheFileList();
        for (File file : files) {
            if (!cacheFiles.contains(file)) {
                Log.i(TAG, "Deleting unused file: " + file.getAbsolutePath());
                boolean result = file.delete();
                if (!result) {
                    Log.w(TAG, "Could not delete file: " + file.getAbsolutePath());
                }
            }
        }
    }

    private synchronized void cleanup() {
        if (cacheSize > maxCacheSize) {
            while (cacheSize > maxCacheSize) {
                Pair<String, DiskCacheObject> oldest = getOldestCacheObject();
                deleteCacheObject(oldest.first, oldest.second);
            }
        }
    }

    /**
     * Loads a new image from the disk cache. If the image that the url points to has already been downloaded, the image will
     * be loaded from the disk. Otherwise, the image will be downloaded first.
     * The image will be stored in the thumbnail cache.
     */
    public synchronized void loadThumbnailBitmap(final String url, final ImageView target) {
        final ImageLoader il = ImageLoader.getInstance();
        if (diskCache != null) {
            DiskCacheObject dco = getFromCacheIfAvailable(url);
            if (dco != null) {
                il.loadThumbnailBitmap(dco.loadImage(), target);
                return;
            }
        }
        target.setTag(KEY_IMAGE_DISK_CACHE, url);
        executor.submit(new ImageDownloader(url) {
            @Override
            protected void onImageLoaded(DiskCacheObject diskCacheObject) {
                if (target.getTag(KEY_IMAGE_DISK_CACHE) == url) {
                    il.loadThumbnailBitmap(diskCacheObject.loadImage(), target);
                }
            }
        });

    }

    /**
     * Loads a new image from the disk cache. If the image that the url points to has already been downloaded, the image will
     * be loaded from the disk. Otherwise, the image will be downloaded first.
     * The image will be stored in the cover cache.
     */
    public synchronized void loadCoverBitmap(final String url, final ImageView target) {
        final ImageLoader il = ImageLoader.getInstance();
        if (diskCache != null) {
            DiskCacheObject dco = getFromCacheIfAvailable(url);
            if (dco != null) {
                il.loadThumbnailBitmap(dco.loadImage(), target);
                return;
            }
        }
        target.setTag(KEY_IMAGE_DISK_CACHE, url);
        executor.submit(new ImageDownloader(url) {
            @Override
            protected void onImageLoaded(DiskCacheObject diskCacheObject) {
                if (target.getTag(KEY_IMAGE_DISK_CACHE) == url) {
                    il.loadCoverBitmap(diskCacheObject.loadImage(), target);
                }
            }
        });
    }

    private synchronized void addToDiskCache(String url, DiskCacheObject obj) {
        if (diskCache == null) {
            initCacheFolder();
        }
        if (AppConfig.DEBUG) Log.d(TAG, "Adding new image to disk cache: " + url);
        diskCache.put(url, obj);
        cacheSize += obj.size;
        if (cacheSize > maxCacheSize) {
            cleanup();
        }
    }

    private synchronized DiskCacheObject getFromCacheIfAvailable(String key) {
        if (diskCache == null) {
            initCacheFolder();
        }
        DiskCacheObject dco = diskCache.get(key);
        if (dco != null) {
            dco.timestamp = System.currentTimeMillis();
        }
        return dco;
    }

    private abstract class ImageDownloader implements Runnable {
        private String downloadUrl;

        public ImageDownloader(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        protected abstract void onImageLoaded(DiskCacheObject diskCacheObject);

        public void run() {
            DiskCacheObject tmp = getFromCacheIfAvailable(downloadUrl);
            if (tmp != null) {
                onImageLoaded(tmp);
                return;
            }

            InputStream input = null;
            OutputStream output = null;
            try {
                URL url = new URL(downloadUrl);
                input = url.openStream();

                File newFile = new File(cacheFolder, Integer.toString(downloadUrl.hashCode()));
                output = new FileOutputStream(newFile);
                long size = IOUtils.copy(input, output);

                final DiskCacheObject dco = new DiskCacheObject(newFile.getAbsolutePath(), size);
                addToDiskCache(downloadUrl, dco);
                onImageLoaded(dco);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                IOUtils.closeQuietly(input);
                IOUtils.closeQuietly(output);
            }
        }
    }

    private static class DiskCacheObject {
        private final String fileUrl;

        /**
         * Last usage of this image cache object.
         */
        private long timestamp;
        private final long size;

        public DiskCacheObject(String fileUrl, long size) {
            if (fileUrl == null) {
                throw new NullPointerException();
            }
            this.fileUrl = fileUrl;
            this.timestamp = System.currentTimeMillis();
            this.size = size;
        }

        public File getFile() {
            return new File(fileUrl);
        }

        public ImageLoader.ImageWorkerTaskResource loadImage() {
            return new ImageLoader.ImageWorkerTaskResource() {
                FileInputStream in = null;

                @Override
                public InputStream openImageInputStream() {
                    try {
                        return new FileInputStream(getFile());
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                    return null;
                }

                @Override
                public InputStream reopenImageInputStream(InputStream input) {
                    if (in != null) {
                        try {
                            in.close();
                            return openImageInputStream();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    return null;
                }

                @Override
                public String getImageLoaderCacheKey() {
                    return fileUrl;
                }
            };
        }
    }
}