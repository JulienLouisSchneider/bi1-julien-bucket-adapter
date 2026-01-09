# BI1 Bucket Adapter — Exemples cURL (routes API)

---

## 1) List — Lister le contenu d’un bucket/prefix

List the objects under remote (bucket or bucket/prefix). The recursive=true parameter includes subfolders.

```bash
curl -G "http://localhost:8081/api/v1/objects"   --data-urlencode "remote=bi1-julien"   --data-urlencode "recursive=true"
```

---

## 2) Create & Update — Upload / overwrite (multipart)

Send a file as multipart/form-data to remote.
remote is passed in the query string, and the file is sent using -F "file=@..."
(command equivalent to Insomnia).

```bash
curl -X POST "http://localhost:8081/api/v1/objects?remote=bi1-julien/test/uploads/InvalidCURL.java"   -F "file=@/Users/julienschneider/Desktop/cpnv-project/BI1/bucket-adapter/README.md"
```

---

## 3) Delete — Delete an object or a prefix

Deletes the target remote.
If remote ends with /, it is generally a prefix; recursive=true deletes everything under that prefix.

```bash
curl -X DELETE "http://localhost:8080/api/v1/objects"   --data-urlencode "remote=bi1-julien/test/uploads/InvalidCURL.java"   --data-urlencode "recursive=false"
```

---

## 4) Share — Generate a temporary (pre-signed) URL

Generates a shareable URL for the remote object, valid for expirationTime seconds.

```bash
curl -G "http://localhost:8080/api/v1/objects/share"   --data-urlencode "remote=bi1-julien/test/uploads/InvalidCURL.java"   --data-urlencode "expirationTime=5"
```

---

## 5) Download — Download an object

Downloads the binary content of the object.

```bash
curl -L -OJ "http://localhost:8080/api/v1/objects/download?remote=bi1-julien/test/upload/InvalidCURL.java"
```

- `-O`: save to a file
- `-J`: respect the filename returned by the `Content-Disposition` header

---
