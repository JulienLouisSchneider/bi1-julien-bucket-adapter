# Bucket Adapter

## Description

Bucket Adapter is a REST microservice built with Spring Boot. Its goal is to communicate with multiple cloud providers  
through a common interface and a Factory-based adapter selection.

The application exposes a REST API. The current AWS and GCP implementations support:

- uploading files
- downloading files
- updating existing files (overwrite)
- deleting files (single object or recursive prefix deletion)
- listing bucket contents
- checking whether an object exists
- generating temporary shareable URLs (pre-signed URLs)

## Getting Started

### Documentation

You must run the application (see Deployment section) in order to access the documentation at the following URL:  
http://localhost:8081/swagger-ui/index.html

Video of kanban :
https://youtu.be/awYhGX692GE

### Prerequisites

The following tools and dependencies are required:

* IDE used IntelliJ `2025.3.1`

* **Language / Runtime**
    * Java JDK 21 `openjdk 21.0.9 2025-10-21`
    * OpenJDK Runtime Environment `(build 21.0.9)`
    * JVM compatible with Java 21

* **Frameworks & Libraries**
    * Spring Boot 4.0.1
    * Spring Framework 7.0.2
    * AWS SDK v2 (S3, Presigner)
    * GCP 26.37.0
    * JUnit 5
    * Mockito

* **Build & Dependency Management**
    * Maven Wrapper (`./mvnw` or `mvn`)

* **IDE used**
    * IntelliJ `2025.3.1`

* **Supported OS (tested)**
    * MacOS (`Tahoe 26.1`)

* **Cloud Providers**
    * AWS S3 (currently implemented)
    * Google Cloud Storage (implemented)
    * Azure Blob Storage (planned)

* **Virtualization**
    * Docker version 28.5.1, build e180ab8

---  

### Configuration

#### Environment variables / system properties

The application relies on external configuration to select the storage provider and access the bucket.

1. Copy the `.env.example` file to a `.env` file using this command : `cp .env.example .env`.
2. Configure variables in `.env` file.

Default application port (override if needed):

```bash
SEREVER_PORT=8090
```

#### AWS configuration

Required variables:

```bash  
AWS_REGION=your-region  
AWS_ACCESS_KEY_ID=your-access-key-id  
AWS_SECRET_ACCESS_KEY=your-secret-access-key  
```  

Provider selection:

```bash  
PROVIDER_IMPL=AWS  
```  

#### GCP configuration

Required variables :

```bash  
GOOGLE_CLOUD_PROJECT=your-project-id  
GOOGLE_APPLICATION_CREDENTIALS=./path-to-credentials.json  
```  

> Note : You'll have to put the path of your `credentials.json` file in the `GOOGLE_APPLICATION_CREDENTIALS`
> environment  
> variable.

Provider selection:

```bash  
PROVIDER_IMPL=GCP  
```  

#### Azure configuration

For next feature.

## Deployment

### On dev environment

#### Build the project

```bash
mvn clean install
```

#### Run tests

```bash
mvn test
```

2. Check for coverage

https://www.jetbrains.com/help/idea/code-coverage.html

#### Run the application

```bash
mvn spring-boot:run
```

### On integration environment

#### Maven build

```bash
# Make sure Maven wrapper is executable
chmod +x mvnw

# Clean and compile, skip tests
mvn clean package -DskipTests

# (Optional) Run tests
mvn test
```

#### Docker build & run

```bash
# Build Docker image
docker compose up --build
```

### How to use the application ?

#### API

##### Insomnia

You can use Insomnia for commands. Import the Insomnia_2026-01-09.yaml file into your Insomnia application.

##### Curl

To use the API you can read this [documentation](docs/curl-route.md).

**How to update the API documentation ?**

To update the documentation, first you'll have to start run the project using **maven** or **docker** :

```bash
# Maven
mvn spring-boot:run

# Docker
docker compose up --build
```

## Directory structure

```bash
.
├── Dockerfile
├── HELP.md
├── Insomnia_2026-01-09.yaml
├── README.md
├── bi1-julien.json
├── checkstyle.xml
├── docker-compose.yml
├── docs
├── mvnw
├── mvnw.cmd
├── package-lock.json
├── pom.xml
├── qodana.yaml
├──  src
│    ├── main
│    │   ├── java
│    │   │   └── com
│    │   │       └── bucketadapter
│    │   │           ├── BucketAdapterApplication.java
│    │   │           ├── BucketAdapterFactory.java
│    │   │           ├── BucketController.java
│    │   │           ├── BucketService.java
│    │   │           ├── adapter
│    │   │           │   ├── BucketAdapter.java
│    │   │           │   └── impl
│    │   │           │       ├── AWSBucketAdapterImpl.java
│    │   │           │       ├── AZUREBucketAdapterImpl.java
│    │   │           │       └── GCPBucketAdapterImpl.java
│    │   │           ├── bucketadapterexceptions
│    │   │           │   ├── ApiExceptionHandler.java
│    │   │           │   ├── BucketObjectNotFoundException.java
│    │   │           │   ├── BucketOperationException.java
│    │   │           │   └── InvalidBucketPathException.java
│    │   │           ├── config
│    │   │           │   ├── AwsClientConfig.java
│    │   │           │   ├── DotenvInitializer.java
│    │   │           │   ├── GcpStorageConfig.java
│    │   │           │   └── OpenApiConfig.java
│    │   │           └── helpers
│    │   │               └── AdapterHelper.java
│    │   └── resources
│    │       ├── application.properties
│    │       ├── static
│    │       └── templates
│    └── test
│        └── java
│            └── com
│                └── bucketadapter
│                    └── bucket_adapter
│                        ├── AWSBucketAdapterTest.java
│                        └── GCPStorageAdapterTest.java
└──  target

```

## Collaborate

### Proposing a new feature

- Create an **issue** describing the feature or bug
- Submit a **Pull Request** linked to the issue

### Commit convention

This project follows [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)

Examples :

```bash
feat: add GCP bucket adapter
fix: handle S3 presigner exception
test: add unit tests for recursive delete
```

### Git branch workflow

This projects use the [Gitflow workflow](https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow)

Examples :

```bash
feature/implement-aws-s3
release/1.0.0
hotfix/fix-servor-error-on-s3-upload
```

## License

* [Choose the license adapted to your project](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository).

## Contact

For questions or contributions:

- GitHub Issues
- Pull Request discussions

For personal interactions:

- Schneider Julien
- julienschneider@eduvaud.ch
