# Development guidelines

This file contains permanent project rules for AI coding agents.

## Project

javanpy is a Java library for reading NumPy `.npy` files.

- Java version: 21
- Build system: Maven
- Test framework: JUnit 5
- Base package: `com.horako.javanpy`

## Project structure

- `src/main/java/com/horako/javanpy/`: library source code
- `src/test/java/com/horako/javanpy/`: JUnit tests
- `pom.xml`: Maven build configuration
- `README.md`: project overview and build instructions
- `.github/workflows/`: continuous integration configuration

## Rules

- Prefer simple, maintainable implementations and follow standard Java conventions.
- Do not introduce new runtime dependencies without approval.
- Include appropriate tests for every feature and bug fix.
- Run `mvn test` after code changes.
- Do not change public APIs without explicit approval.
- Stay within the scope of the current issue or task.
- Explain the implementation plan before making changes.
- Summarize changes and test results after completing a task.
- Do not commit, push, or create releases without explicit permission.
- Agents may modify source code, tests, and other project files when required by
  an approved task.
- Restrictions on which files may be modified apply only to the specific task
  for which they were given.
