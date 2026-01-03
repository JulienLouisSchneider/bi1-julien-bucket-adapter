package com.bucketadapter.adapter.impl;
import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("AWS")
public class AWSBucketAdapterImpl implements BucketAdapter {
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
        return List.of(
                remote + "/",
                remote + "/README.txt",
                remote + "/images/logo.png",
                remote + "/docs/specs.pdf",
                remote + "/uploads/2026-01-03/report.csv",
                remote + "/logs/app-2026-01-03.log",
                remote + "/archive/2025/backup.zip"
        );
    }

    @Override
    public String share(String remote, int expirationTime) {
        return "";
    }
}
