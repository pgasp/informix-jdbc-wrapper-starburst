# Informix JDBC Wrapper for Starburst

Thin JDBC driver wrapper that makes the IBM Informix driver compatible with Starburst Enterprise's `generic-jdbc` connector.

## Problem

Starburst Enterprise's `BaseJdbcConfig` rejects JDBC URLs containing a hyphen in the sub-protocol. The IBM Informix driver requires `jdbc:informix-sqli://...`, which is blocked by this validation.

## Solution

This wrapper registers itself under `jdbc:informix:` (no hyphen) and transparently rewrites the URL to `jdbc:informix-sqli:` before delegating to IBM's `IfxDriver`. No patching of SEP internals, no recompilation of Starburst.

## Build

```bash
mvn clean package
```

Requires `ifxjdbc.jar` in your local Maven repo or installed via:
```bash
mvn install:install-file \
  -Dfile=ifxjdbc.jar \
  -DgroupId=com.ibm.informix \
  -DartifactId=jdbc \
  -Dversion=4.10.13 \
  -Dpackaging=jar
```

## Deployment on Starburst Enterprise (Kubernetes)

Deploy **both JARs** into the `generic-jdbc` plugin directory:

```
/usr/lib/starburst/plugin/generic-jdbc/informix-jdbc-wrapper-1.0.0.jar
/usr/lib/starburst/plugin/generic-jdbc/ifxjdbc.jar
/usr/lib/starburst/plugin/generic-jdbc/ifxjdbc.lic   # if applicable
```

### catalog-values.yaml

```yaml
informix: |
  connector.name=generic-jdbc
  generic-jdbc.driver-class=io.starburst.jdbc.informix.InformixWrapperDriver
  connection-url=jdbc:informix://HOST:PORT/DATABASE:INFORMIXSERVER=SERVER_NAME
  connection-user=${ENV:INFORMIX_USER}
  connection-password=${ENV:INFORMIX_PASSWORD}
  metadata.cache-ttl=30m
  metadata.cache-missing=true
  case-insensitive-name-matching=true
```

Replace `HOST`, `PORT`, `DATABASE`, and `SERVER_NAME` with actual values.

## How it works

```
SEP generic-jdbc
  └─ InformixWrapperDriver.connect("jdbc:informix://host:port/db:INFORMIXSERVER=srv", props)
       └─ rewrites URL → "jdbc:informix-sqli://host:port/db:INFORMIXSERVER=srv"
       └─ DriverManager.getConnection(rewrittenUrl, props)
            └─ IBM IfxDriver handles the actual connection
```
