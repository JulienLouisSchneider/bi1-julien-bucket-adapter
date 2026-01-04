package com.bucketadapter;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
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
      @RequestParam String remote, @RequestParam(defaultValue = "false") boolean recursive) {
    return bucketService.list(remote, recursive);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public void upload(@RequestParam String remote, @RequestPart("file") MultipartFile file) {
    try {
      bucketService.upload(remote, file.getBytes());
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read uploaded file", e);
    }
  }

  @DeleteMapping(params = "remote")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @RequestParam String remote, @RequestParam(defaultValue = "false") boolean recursive) {
    bucketService.delete(remote, recursive);
  }
}
