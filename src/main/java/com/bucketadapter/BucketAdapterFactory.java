package com.bucketadapter;

import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BucketAdapterFactory {

    @Autowired
    private Map<String, BucketAdapter> adapters;

    @Value("${app.provider-impl:AWS}")
    private String provider;


    public BucketAdapter getAdapter() {

        BucketAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "Unsupported provider: " + provider + ". Available: " + adapters.keySet()
            );
        }
        return adapter;
    }
}
