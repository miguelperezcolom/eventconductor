# Workflow Engine
This is a workflow engine built with Spring Boot and Mateu.

## Structure
This project contains several modules:

- app: an springboot mvc app
- forms-engine: a forms engine, to be run inside a springboot mvc app
- workflow-engine: a workflow engine, to be run inside a springboot mvc app
- sample-worker: a hello world worker, to be run inside a springboot mvc app


## Build
From the root project folder:
```shell
mvn clean install
```

## Run
From the root project folder:
```shell
java -jar app/target/app-0.0.1-SNAPSHOT.jar
```

## Test urls

https://riu-com-copy.miguelperezcolom.workers.dev/faqs/index_ES.html
https://riu-com-copy.miguelperezcolom.workers.dev/home/index_ES.html



