package com.bucketadapter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.List;

@RestController
@RequestMapping("/api/v1/objects")
public class BucketController {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "BucketService est injecté par Spring (bean); le controller ne l’expose pas.")
  private final BucketService bucketService;

  public BucketController(BucketService bucketService) {
    this.bucketService = bucketService;
  }

  @Operation(
      summary = "Lister des objets",
      description =
          "Liste les objets sous le préfixe remote. Utiliser recursive=true pour inclure les sous-dossiers.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Liste retournée"),
    @ApiResponse(responseCode = "400", description = "Chemin remote invalide"),
    @ApiResponse(responseCode = "404", description = "Bucket/objet introuvable"),
    @ApiResponse(responseCode = "500", description = "Erreur interne")
  })
  @GetMapping(params = "remote")
  public List<String> list(
      @RequestParam String remote, @RequestParam(defaultValue = "false") boolean recursive) {
    return bucketService.list(remote, recursive);
  }

  @Operation(summary = "Upload d’un objet")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Objet créé"),
    @ApiResponse(responseCode = "400", description = "Chemin remote invalide"),
    @ApiResponse(responseCode = "404", description = "Bucket/objet introuvable")
  })
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public void upload(@RequestParam String remote, @RequestPart("file") MultipartFile file) {
    try {
      bucketService.upload(remote, file.getBytes());
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read uploaded file", e);
    }
  }

  @Operation(
      summary = "Supprimer un objet",
      description =
          "Supprime l'objet ciblé par remote. Si remote pointe vers un préfixe, utiliser recursive=true.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Objet supprimé"),
    @ApiResponse(responseCode = "400", description = "Chemin remote invalide"),
    @ApiResponse(responseCode = "404", description = "Bucket/objet introuvable"),
    @ApiResponse(responseCode = "500", description = "Erreur interne")
  })
  @DeleteMapping(params = "remote")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @RequestParam String remote, @RequestParam(defaultValue = "false") boolean recursive) {
    bucketService.delete(remote, recursive);
  }

  @Operation(
      summary = "Partager un objet",
      description =
          "Retourne une URL pré-signée pour l'objet remote, valable expirationTime secondes.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "URL pré-signée retournée"),
    @ApiResponse(responseCode = "400", description = "Chemin remote invalide"),
    @ApiResponse(responseCode = "404", description = "Bucket/objet introuvable"),
    @ApiResponse(responseCode = "500", description = "Erreur interne")
  })
  @GetMapping(
      value = "/share",
      params = {"remote", "expirationTime"})
  public String share(@RequestParam String remote, @RequestParam int expirationTime) {
    return bucketService.share(remote, expirationTime);
  }

  @Operation(
      summary = "Télécharger un objet",
      description = "Télécharge l'objet remote en binaire (application/octet-stream).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Contenu binaire retourné"),
    @ApiResponse(responseCode = "400", description = "Chemin remote invalide"),
    @ApiResponse(responseCode = "404", description = "Bucket/objet introuvable"),
    @ApiResponse(responseCode = "500", description = "Erreur interne")
  })
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
