package com.bucketadapter.adapter;

import java.util.List;

public interface BucketAdapter {
    /**
     * Upload an object to a remote path/key.
     *
     * @param remote remote path/key in the bucket (e.g., "folder/file.txt")
     * @param object file content as bytes
     */
    void upload(String remote, byte[] object);

    /**
     * Download an object content as bytes from a remote path/key.
     *
     * @param remote remote path/key in the bucket (e.g., "folder/file.txt")
     * @return object content as bytes
     */
    byte[] download(String remote);

    /**
     * Delete an object (or a "folder" prefix if recursive=true) from a remote path/key.
     *
     * @param remote remote path/key (or prefix)
     * @param recursive if true, delete all objects under the prefix (best-effort depending on provider)
     */
    void delete(String remote, boolean recursive);

    /**
     * List objects under a remote prefix.
     *
     * @param remote remote prefix (e.g., "folder/")
     * @param recursive if true, list all descendants; otherwise only direct children (provider-dependent)
     * @return list of keys/paths found
     */
    List<String> list(String remote, boolean recursive);

    /**
     * Create a temporary share URL (pre-signed URL typically).
     *
     * @param remote remote path/key
     * @param expirationTime expiration in seconds
     * @return share URL
     */
    String share(String remote, int expirationTime);
}
