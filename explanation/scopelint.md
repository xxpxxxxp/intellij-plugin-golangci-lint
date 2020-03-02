# scopelint

**This explanation is from [kyoh86/scopelint](https://github.com/kyoh86/scopelint) with better formatting**

**scopelint** checks for unpinned variables in go programs.

## What's this?

```go
values := []string{"a", "b", "c"}
var funcs []func()
for _, val := range values {
	funcs = append(funcs, func() {
		fmt.Println(val)
	})
}
for _, f := range funcs {
	f()
}
/*output:
c
c
c
*/
var copies []*string
for _, val := range values {
	copies = append(copies, &val)
}
/*(in copies)
&"c"
&"c"
&"c"
*/
for _, copy := range copies {
	fmt.Println(*copy)
}
/*output:
c
c
c
*/
```

In Go, the `val` variable in the above loops is actually a single variable.
So in many case (like the above), using it makes annoying bugs.

#### Fixed sample:

```go
values := []string{"a", "b", "c"}
var funcs []func()
for _, val := range values {
	val := val // pin!
	funcs = append(funcs, func() {
		fmt.Println(val)
	})
}
for _, f := range funcs {
	f()
}

var copies []*string
for _, val := range values {
	val := val // pin!
	copies = append(copies, &val)
}
for _, copy := range copies {
	fmt.Println(*copy)
}
```