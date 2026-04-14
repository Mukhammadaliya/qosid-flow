const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 9000;
const DATA_DIR = process.env.S3_DATA_DIR || path.join(__dirname, "s3-data");
const BUCKETS = (process.env.S3_BUCKETS || "gateway-files,qosid-temp,qosid-permanent").split(",");

// Create bucket directories
BUCKETS.forEach((b) => {
  const dir = path.join(DATA_DIR, b.trim());
  fs.mkdirSync(dir, { recursive: true });
});

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const parts = url.pathname.split("/").filter(Boolean);
  const bucket = parts[0];
  const key = parts.slice(1).join("/");
  const filePath = bucket && key ? path.join(DATA_DIR, bucket, key) : null;

  // HEAD on bucket (no key) — bucketExists check (MinIO SDK uses this)
  if (req.method === "HEAD" && bucket && !key) {
    const bucketDir = path.join(DATA_DIR, bucket);
    if (fs.existsSync(bucketDir)) {
      console.log(`HEAD   200 /${bucket}/   (bucket exists)`);
      res.writeHead(200);
    } else {
      console.log(`HEAD   404 /${bucket}/   (bucket not found)`);
      res.writeHead(404);
    }
    res.end();
    return;
  }

  // GET on bucket (no key) — listObjects / bucket location (MinIO SDK uses this)
  if (req.method === "GET" && bucket && !key) {
    const bucketDir = path.join(DATA_DIR, bucket);
    if (url.searchParams.has("location")) {
      console.log(`GET    200 /${bucket}/?location   (bucket location)`);
      res.writeHead(200, { "Content-Type": "application/xml" });
      res.end('<?xml version="1.0" encoding="UTF-8"?><LocationConstraint xmlns="http://s3.amazonaws.com/doc/2006-03-01/"></LocationConstraint>');
      return;
    }
    if (fs.existsSync(bucketDir)) {
      console.log(`GET    200 /${bucket}/   (list bucket)`);
      res.writeHead(200, { "Content-Type": "application/xml" });
      res.end('<?xml version="1.0" encoding="UTF-8"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>' + bucket + '</Name></ListBucketResult>');
    } else {
      res.writeHead(404);
      res.end('<Error><Code>NoSuchBucket</Code></Error>');
    }
    return;
  }

  // HEAD — check if object exists
  if (req.method === "HEAD" && filePath) {
    if (fs.existsSync(filePath)) {
      const stat = fs.statSync(filePath);
      console.log(`HEAD   200 /${bucket}/${key}   ${stat.size}B (exists)`);
      res.writeHead(200, { "Content-Length": stat.size });
    } else {
      console.log(`HEAD   404 /${bucket}/${key}   (not found)`);
      res.writeHead(404);
    }
    res.end();
    return;
  }

  // PUT — upload object
  if (req.method === "PUT" && filePath) {
    const dir = path.dirname(filePath);
    fs.mkdirSync(dir, { recursive: true });
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const body = Buffer.concat(chunks);
      fs.writeFileSync(filePath, body);
      console.log(`PUT    200 /${bucket}/${key}   ${body.length}B -> ${filePath}`);
      res.writeHead(200, { ETag: `"${Date.now()}"` });
      res.end();
    });
    return;
  }

  // GET — download object
  if (req.method === "GET" && filePath) {
    if (fs.existsSync(filePath)) {
      const data = fs.readFileSync(filePath);
      console.log(`GET    200 /${bucket}/${key}   ${data.length}B`);
      res.writeHead(200, { "Content-Length": data.length });
      res.end(data);
    } else {
      console.log(`GET    404 /${bucket}/${key}`);
      res.writeHead(404);
      res.end("<Error><Code>NoSuchKey</Code></Error>");
    }
    return;
  }

  // DELETE — remove object
  if (req.method === "DELETE" && filePath) {
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
      console.log(`DELETE 200 /${bucket}/${key}`);
    } else {
      console.log(`DELETE 404 /${bucket}/${key}`);
    }
    res.writeHead(204);
    res.end();
    return;
  }

  // Fallback
  console.log(`${req.method} 400 ${req.url} (unsupported)`);
  res.writeHead(400);
  res.end("Unsupported");
});

server.listen(PORT, () => {
  console.log("S3 mock server running");
  console.log(`Port: ${PORT}`);
  console.log(`Data dir: ${DATA_DIR}`);
  console.log(`Buckets: ${BUCKETS.join(", ")}`);
});
