//go:build darwin || linux || windows

package oomprofile

import "errors"

func WriteFile(_ string, _ string) error {
  return errors.New("oom profiling disabled in this build")
}
