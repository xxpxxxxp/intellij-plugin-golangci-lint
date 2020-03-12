
# scopelint

**This explanation is from [kyoh86/scopelint](https://github.com/kyoh86/scopelint) with better format**

**scopelint** checks for unpinned variables in go programs. Although it tends to misreport a lot, it's highly recommand to check every such issue, **sometimes it'll really save your ass:**

> [Let's Encrypt to revoke 3 million certificates on March 4 due to software bug](https://www.zdnet.com/article/lets-encrypt-to-revoke-3-million-certificates-on-march-4-due-to-bug/)  
> The bug is merged into Boulder CAA code in [sa.go, line 2250](https://github.com/letsencrypt/boulder/pull/4134/files#diff-2285b0268539881fde96d9928ecef358R2250)

> [一行Golang代码引发的血案](https://zhuanlan.zhihu.com/p/111639968)

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
