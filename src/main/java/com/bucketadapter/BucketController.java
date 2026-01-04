package com.bucketadapter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

  @GetMapping(
      value = "/share",
      params = {"remote", "expirationTime"})
  public String share(@RequestParam String remote, @RequestParam int expirationTime) {
    return bucketService.share(remote, expirationTime);
  }

  @GetMapping(value = "/download", params = "remote")
  public ResponseEntity<byte[]> download(@RequestParam String remote) {
    byte[] data = bucketService.download(remote);

    String filename = remote.substring(remote.lastIndexOf('/') + 1);

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + filename.replace("\"", "") + "\"")
        .body(data);
  }
}
