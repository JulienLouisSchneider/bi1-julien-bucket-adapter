package com.bucketadapter.adapter.impl;

import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("GCP")
public class GCPBucketAdapterImpl implements BucketAdapter {
    @Override
    public void upload(String remote, byte[] object) {

    }

    @Override
    public byte[] download(String remote) {
        return new byte[0];
    }

    @Override
    public void delete(String remote, boolean recursive) {

    }

    @Override
    public List<String> list(String remote, boolean recursive) {
        return List.of();
    }

    @Override
    public String share(String remote, int expirationTime) {
        return "";
    }
}
