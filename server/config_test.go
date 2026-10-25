package main

import (
	"os"
	"testing"
)

func TestNormalizePath(t *testing.T) {
	cases := []struct{ in, want string }{
		{`C:\`, `C:` + string(os.PathSeparator)}, // 盘符根必须保留
		{`C:/`, `C:` + string(os.PathSeparator)},
		{`C:`, `C:`},
		{`C:\Users\Me`, `C:\Users\Me`},
		{`C:\Users\Me\`, `C:\Users\Me`},
		{`D:\共享`, `D:\共享`},
		{`D:\共享\`, `D:\共享`},
		{`/`, `/`},
		{`\`, `\`},
		{``, ``},
		{`.`, `.`},
		{`/home/user/data/`, `/home/user/data`},
	}
	for _, c := range cases {
		got := normalizePath(c.in)
		if got != c.want {
			t.Errorf("normalizePath(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
