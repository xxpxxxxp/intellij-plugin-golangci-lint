
# go-err113

**This explanation is from [Djarvur/go-err113](https://github.com/Djarvur/go-err113) with detail explanations**

**go-err113** is in general a best practice, not a bug.

## What's this?

Starting from Go 1.13 the standard `error` type behaviour was changed: one `error` could be derived from another with `fmt.Errorf()` method using `%w` format specifier.

So the errors hierarchy could be built for flexible and responsible errors processing.

And to make this possible at least two simple rules should be followed:

1. `error` values **should not be compared directly** but with `errors.Is()` method.
1. `error` should not be created dynamically from scratch but by the wrapping the static (package-level) error.

This linter is checking the code for these 2 rules compliance.

### Reports

So, `err113` reports every `==` and `!=` comparison for exact `error` type variables except comparison to `nil` and `io.EOF`.

Also, any call of `errors.New()` and `fmt.Errorf()` methods are reported except the calls used to initialise package-level variables and the `fmt.Errorf()` calls wrapping the other errors.

Note: non-standard packages, like `github.com/pkg/errors` are ignored complitely.

### Examples

Instead of
```go
return errors.New("some msg")"
// or
return fmt.Errorf("some variable %q", v)"
```

Define a package-level error, use it directly or wrap it
```go
// package-level
var ErrPermission = errors.New("permission denied")

func xxx() error {
  ...
  return ErrPermission
  // or
  return fmt.Errorf("access denied: %w", ErrPermission)
  ...
}
```

Then you are able to use `errors.Is` or `errors.As` to check error
```go
if errors.Is(err, ErrPermission) {
    // err, or some error that it wraps, is a permission problem
}
```