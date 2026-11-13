# Project Context for Claude Code

## Stack
- Java/Kotlin (JVM)

## Before formatting anything
Check for existing config first and defer to it over anything below:
- `.editorconfig`
- IntelliJ code style scheme in `.idea/codeStyles/`
- `checkstyle.xml`, `.ktlint`, or `spotless` config in the build file
If one of these exists, follow it exactly and ignore any conflicting rule below.
If none exist, use the style guide below.

## General style
- 4-space indentation, no tabs
- Max line length: 120 characters
- Opening brace on the same line as the statement (K&R style), not on its own line
- One blank line between methods, no blank line at the start of a class/method body

## Naming
- Classes/interfaces: PascalCase
- Methods/variables: camelCase
- Constants: UPPER_SNAKE_CASE
- Booleans read as a question: `isActive`, `hasPermission`, not `active`, `permission`

## Example: preferred style (Java)
```java
public Optional<User> findActiveUser(String id) {
    return userRepository.findById(id)
        .filter(User::isActive);
}
```

## Avoid this style
```java
public Optional<User> findActiveUser(String id)
{
    Optional<User> user = userRepository.findById(id);
    if (user.isPresent() && user.get().isActive())
    {
        return user;
    }
    return Optional.empty();
}
```

## Example: preferred style (Kotlin)
```kotlin
fun findActiveUser(
    id: String
): User? = userRepository
    .findById(id)
    ?.takeIf { 
        it.isActive 
    }
```

```kotlin
OutlinedTextField(
    value = uiState.emailAddress
    , onValueChange = viewModel::onEmailChange
    , label = { Text("Email Address") }
    , singleLine = true
    , keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
    , modifier = Modifier.fillMaxWidth()
)
```


## Avoid this style
```kotlin
fun findActiveUser(id: String): User? {
    val user = userRepository.findById(id)
    if (user != null && user.isActive) {
        return user
    }
    return null
}
```

## Comments
- Prefer self-explanatory code over comments
- Use comments only to explain *why*, not *what*
- No commented-out code left in commits

## After making changes
- Run the project's formatter/linter if one is configured (e.g. `./gradlew spotlessApply` or `ktlintFormat`) before considering a task done
- If no formatter is configured, match the style rules above by hand

