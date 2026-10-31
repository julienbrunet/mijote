// Package images turns uploaded pictures into small JPEG thumbnails stored in
// the image directory. Images are never stored in the database and never
// fetched from the outside: clients upload them (requirements.md §9).
package images

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	_ "image/gif" // accepted input formats
	"image/jpeg"
	_ "image/png"
	"io"
	"os"
	"path/filepath"
	"regexp"
)

const (
	// MaxUpload is the largest accepted upload, in bytes.
	MaxUpload = 10 << 20
	// MaxSide is the longest side of a stored thumbnail, in pixels.
	MaxSide = 800
	// maxPixels bounds the decoded size so a small file cannot exhaust the memory of a Raspberry Pi.
	maxPixels = 16_000_000
	quality   = 80
)

// ErrInvalid is returned for data that is not a usable JPEG, PNG or GIF picture.
var ErrInvalid = errors.New("invalid image")

// Thumbnail decodes a JPEG, PNG or GIF picture and returns it as a JPEG of at
// most MaxSide pixels on its longest side, with its short content hash.
func Thumbnail(r io.Reader) (data []byte, hash string, err error) {
	raw, err := io.ReadAll(io.LimitReader(r, MaxUpload+1))
	if err != nil {
		return nil, "", err
	}
	if len(raw) > MaxUpload {
		return nil, "", fmt.Errorf("%w: file larger than %d MB", ErrInvalid, MaxUpload>>20)
	}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil || cfg.Width <= 0 || cfg.Height <= 0 {
		return nil, "", fmt.Errorf("%w: unsupported format (use JPEG, PNG or GIF)", ErrInvalid)
	}
	if cfg.Width*cfg.Height > maxPixels {
		return nil, "", fmt.Errorf("%w: picture too large", ErrInvalid)
	}
	src, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, "", fmt.Errorf("%w: unreadable picture", ErrInvalid)
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, scaleDown(src, MaxSide), &jpeg.Options{Quality: quality}); err != nil {
		return nil, "", err
	}
	sum := sha256.Sum256(out.Bytes())
	return out.Bytes(), hex.EncodeToString(sum[:4]), nil
}

// scaleDown shrinks src so that its longest side is at most max, averaging
// the source pixels of each target pixel (box filter). Transparency is
// flattened onto white.
func scaleDown(src image.Image, max int) *image.RGBA {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	nw, nh := w, h
	if w > max || h > max {
		if w >= h {
			nw, nh = max, max*h/w
		} else {
			nw, nh = max*w/h, max
		}
		nw, nh = max1(nw), max1(nh)
	}
	flat := image.NewRGBA(b)
	draw.Draw(flat, b, image.NewUniform(color.White), image.Point{}, draw.Src)
	draw.Draw(flat, b, src, b.Min, draw.Over)
	if nw == w && nh == h {
		return flat
	}
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	for y := 0; y < nh; y++ {
		y0, y1 := y*h/nh, max1((y+1)*h/nh)
		for x := 0; x < nw; x++ {
			x0, x1 := x*w/nw, max1((x+1)*w/nw)
			var r, g, bl, n int
			for sy := y0; sy < y1; sy++ {
				for sx := x0; sx < x1; sx++ {
					c := flat.RGBAAt(b.Min.X+sx, b.Min.Y+sy)
					r, g, bl, n = r+int(c.R), g+int(c.G), bl+int(c.B), n+1
				}
			}
			dst.SetRGBA(x, y, color.RGBA{uint8(r / n), uint8(g / n), uint8(bl / n), 255})
		}
	}
	return dst
}

func max1(v int) int { return max(v, 1) }

// Dir is the image directory.
type Dir struct{ Path string }

var nameRe = regexp.MustCompile(`^[0-9a-f-]{36}-[0-9a-f]{8}\.jpg$`)

// Name is the file name of a recipe's thumbnail: the hash changes with the
// content, so the URL built from it can be cached forever.
func Name(recipeID, hash string) string { return recipeID + "-" + hash + ".jpg" }

// Save writes a thumbnail atomically and returns its file name.
func (d Dir) Save(recipeID, hash string, data []byte) (string, error) {
	name := Name(recipeID, hash)
	if !nameRe.MatchString(name) {
		return "", ErrInvalid
	}
	if err := os.MkdirAll(d.Path, 0o750); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(d.Path, ".upload-*")
	if err != nil {
		return "", err
	}
	_, err = tmp.Write(data)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Chmod(tmp.Name(), 0o640)
	}
	if err == nil {
		err = os.Rename(tmp.Name(), filepath.Join(d.Path, name))
	}
	if err != nil {
		os.Remove(tmp.Name()) //nolint:errcheck
		return "", err
	}
	return name, nil
}

// Remove deletes a thumbnail; a missing file is not an error.
func (d Dir) Remove(name string) {
	if nameRe.MatchString(name) {
		os.Remove(filepath.Join(d.Path, name)) //nolint:errcheck
	}
}

// Open opens a thumbnail by file name.
func (d Dir) Open(name string) (*os.File, error) {
	if !nameRe.MatchString(name) {
		return nil, os.ErrNotExist
	}
	return os.Open(filepath.Join(d.Path, name))
}
