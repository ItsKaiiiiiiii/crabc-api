# AGENTS.md - Coding Guidelines for crabc-api

## Project Overview

A Java Spring Boot API development platform with multi-module Maven architecture for dynamic SQL-based API generation.

## Build Commands

```bash
# Compile all modules
mvn clean compile

# Run tests (no tests currently exist)
mvn test

# Run a single test class (when tests are added)
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName

# Package application
mvn clean package

# Package without tests
mvn clean package -DskipTests

# Run the application
cd crabc-api/crabc-admin && mvn spring-boot:run
```

## Project Structure

```
cn.crabc
├── crabc-api/               # API modules parent
│   ├── crabc-admin/         # Boot module (port 9377)
│   ├── crabc-core/          # Core business logic
│   ├── crabc-datasource/    # Dynamic data sources
│   └── crabc-spi/           # Plugin interfaces
└── crabc-spring-boot-starter/  # Maven starter (optional)
```

## Technology Stack

- **Java**: 17
- **Spring Boot**: 3.5.9
- **MyBatis**: 3.0.5 (with MyBatis-Plus 3.5.15)
- **Database**: MySQL 8.0+, Oracle, PostgreSQL, SQL Server, TiDB
- **Connection Pool**: Druid 1.2.27
- **Cache**: Caffeine
- **JSON**: Fastjson 2.0.49
- **Auth**: JWT 0.12.5
- **Utilities**: Lombok, Apache Commons

## Code Style Guidelines

### Naming Conventions

- **Packages**: `cn.crabc.core.{module}.{layer}` (e.g., `cn.crabc.core.app.controller`)
- **Classes**: PascalCase (e.g., `DataSourceController`, `BaseApiService`)
- **Interfaces**: PascalCase with `I` prefix (e.g., `IBaseDataService`, `IApiMapper`)
- **Methods**: camelCase (e.g., `getDataSourcePage`, `testConnection`)
- **Variables**: camelCase (e.g., `dataSourceId`, `pageNum`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `CODE_TAG`, `MSG_TAG`)

### Code Formatting

- **Indentation**: 4 spaces (no tabs)
- **Braces**: Allman style (opening brace on new line for class/method)
- **Line endings**: LF
- **Encoding**: UTF-8
- **Max line length**: 120 characters

### Import Organization

1. `java.*` and `javax.*`
2. Third-party libraries (Spring, MyBatis, etc.)
3. Project internal imports
4. Static imports (if any)

### Documentation

- All classes must have class-level Javadoc with:
  - Description in Chinese
  - `@author yuqf` tag
- All public methods should have Javadoc with Chinese descriptions
- Use block comments for complex logic sections

### Dependency Injection

- Use `@Autowired` for field injection (existing pattern)
- Prefer constructor injection for new code when possible

### REST API Controllers

- Use `@RestController` annotation
- Base path pattern: `/api/box/{module}/{resource}`
- Return `Result` wrapper for consistent API responses
- HTTP methods:
  - `GET` for queries with `@GetMapping`
  - `POST` for create with `@PostMapping`
  - `PUT` for update with `@PutMapping`
  - `DELETE` for delete with `@DeleteMapping`

### Entities and DTOs

- Use Lombok `@Getter` and `@Setter` annotations
- Include `serialVersionUID` for serializable classes
- Use `Long` for primary keys
- Database column names: use underscores (e.g., `api_id`)
- Java field names: use camelCase (e.g., `apiId`)

### MyBatis Mappers

- Place XML files in `src/main/resources/mapper/`
- Namespace must match mapper interface fully qualified name
- Use `<if>` tags for dynamic SQL conditions
- Parameter references: `#{fieldName}`
- Use `resultType` for selects, `parameterType` for inserts/updates

### Error Handling

- Use `Result.error()` for business errors
- Return HTTP 200 with error code in response body
- Success code: `0`, Error code: `500` or custom

### Database Guidelines

- Support multiple database types (MySQL, Oracle, PostgreSQL, etc.)
- Use parameterized queries (no string concatenation)
- Use PageHelper for pagination

## Testing

- Framework: JUnit 4 (from dependencies)
- Spring Boot Test for integration tests
- Test classes should follow `*Test.java` or `Test*.java` naming
- Place tests in `src/test/java` matching package structure

## Configuration

- Application config: `application.yml`
- MyBatis config: `mybatis-config.xml`
- Port: 9377 (development)
- Banner mode: off (`banner-mode: off`)

## Additional Notes

- This is a Chinese project with Chinese comments and documentation
- Licensed under Apache License 2.0
- No automated linting tools (checkstyle/spotbugs) currently configured
- Focus on dynamic SQL execution and API generation capabilities
