package com.bucketadapter;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/objects")
public class BucketController {

  private final BucketService bucketService;

  public BucketController(BucketService bucketService) {
    this.bucketService = bucketService;
  }

  @GetMapping(params = "remote")
  public List<String> list(
          @RequestParam String remote,
          @RequestParam(defaultValue = "false") boolean recursive
  ) {
    return bucketService.list(remote, recursive);
  }
}
