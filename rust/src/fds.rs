//! Klipp's services for the engine: like FilmCraft's `FsServices`, but files that Klipp passes as `/proc/self/fd/N` are read from
//! the already open handle (a duplicate of it, positional reads) instead of being reopened via the path.
//!
//! Android doesn't let the app reopen a media file via `/proc/self/fd/N` after a restart when the file came from
//! the photo picker (no storage permission) – the handle from the content provider, however, can be read. Without this,
//! such clips became "missing" for the engine after a restart: silent audio in metering, ducking and the engine's export.

use std::fs::File;
use std::io;
use std::os::fd::BorrowedFd;
use std::os::unix::fs::FileExt;
use std::sync::Arc;

use filmcraft_engine::{FsServices, Services};
use filmcraft_engine::media_browser::{DirEntry, Volume};
use filmcraft_media::{ByteReader, SharedReader};

/// The handle's number in a path `/proc/self/fd/N`.
pub fn fd_of(path: &str) -> Option<i32> {
    path.strip_prefix("/proc/self/fd/")?.parse::<i32>().ok().filter(|n| *n >= 0)
}

/// A private duplicate of the handle (closed when the duplicate is dropped; positional reads don't affect the original).
fn dup(fd: i32) -> io::Result<File> {
    // SAFETY: the handle is only borrowed to be duplicated; if it isn't open, duplication returns an error (EBADF)
    let owned = unsafe { BorrowedFd::borrow_raw(fd) }.try_clone_to_owned()?;
    let f = File::from(owned);
    if !f.metadata()?.is_file() {
        return Err(io::Error::new(io::ErrorKind::NotFound, format!("/proc/self/fd/{fd} is not a file")));
    }
    Ok(f)
}

/// Reading from an open handle, anywhere in the file.
struct FdReader {
    file: File,
    len: u64,
}

impl ByteReader for FdReader {
    fn len(&self) -> u64 {
        self.len
    }
    fn read_at(&self, offset: u64, buf: &mut [u8]) -> io::Result<()> {
        if offset.checked_add(buf.len() as u64).is_none_or(|end| end > self.len) {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "read past end of file"));
        }
        self.file.read_exact_at(buf, offset)
    }
}

fn read_all(fd: i32) -> io::Result<Vec<u8>> {
    let f = dup(fd)?;
    let len = f.metadata()?.len() as usize;
    let mut out = vec![0u8; len];
    f.read_exact_at(&mut out, 0)?;
    Ok(out)
}

pub struct KlippServices;

impl Services for KlippServices {
    fn read_file(&self, path: &str) -> io::Result<Vec<u8>> {
        match fd_of(path) {
            Some(fd) => read_all(fd),
            None => FsServices.read_file(path),
        }
    }
    fn write_file(&self, path: &str, data: &[u8]) -> io::Result<()> {
        FsServices.write_file(path, data)
    }
    fn file_size(&self, path: &str) -> io::Result<u64> {
        match fd_of(path) {
            Some(fd) => Ok(dup(fd)?.metadata()?.len()),
            None => FsServices.file_size(path),
        }
    }
    fn read_range(&self, path: &str, offset: u64, len: usize) -> io::Result<Vec<u8>> {
        let Some(fd) = fd_of(path) else { return FsServices.read_range(path, offset, len) };
        let f = dup(fd)?;
        let size = f.metadata()?.len();
        let a = offset.min(size);
        let n = (len as u64).min(size - a) as usize;
        let mut out = vec![0u8; n];
        f.read_exact_at(&mut out, a)?;
        Ok(out)
    }
    fn reader(&self, path: &str) -> Option<io::Result<SharedReader>> {
        let Some(fd) = fd_of(path) else { return FsServices.reader(path) };
        Some(dup(fd).and_then(|file| {
            let len = file.metadata()?.len();
            Ok(Arc::new(FdReader { file, len }) as SharedReader)
        }))
    }
    fn list_dir(&self, dir: &str) -> Option<io::Result<Vec<String>>> {
        FsServices.list_dir(dir)
    }
    fn file_loader(&self) -> Option<filmcraft_media::sequence::FrameLoader> {
        Some(Arc::new(|p: &str| match fd_of(p) {
            Some(fd) => read_all(fd),
            None => std::fs::read(p),
        }))
    }
    fn export_in_memory(&self) -> bool {
        FsServices.export_in_memory()
    }
    fn list_entries(&self, dir: &str) -> Option<io::Result<Vec<DirEntry>>> {
        FsServices.list_entries(dir)
    }
    fn volumes(&self) -> Vec<Volume> {
        FsServices.volumes()
    }
    fn home_dir(&self) -> Option<String> {
        FsServices.home_dir()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::fd::AsRawFd;

    #[test]
    fn reads_through_the_open_handle_without_reopening() {
        let dir = std::env::temp_dir().join(format!("klipp-fd-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("a.bin");
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::File::create(&p).unwrap().write_all(&data).unwrap();
        let f = File::open(&p).unwrap();
        // the file is deleted: only the handle remains (as when the path can't be reopened)
        std::fs::remove_file(&p).unwrap();
        let path = format!("/proc/self/fd/{}", f.as_raw_fd());
        let s = KlippServices;
        assert_eq!(s.file_size(&path).unwrap(), data.len() as u64);
        assert_eq!(s.read_file(&path).unwrap(), data);
        assert_eq!(s.read_range(&path, 99_990, 100).unwrap(), data[99_990..].to_vec());
        let r = s.reader(&path).unwrap().unwrap();
        let mut b = [0u8; 16];
        r.read_at(50_000, &mut b).unwrap();
        assert_eq!(&b[..], &data[50_000..50_016]);
        assert!(r.read_at(99_995, &mut b).is_err(), "past the end");
        // two readers at once don't affect each other (no shared position)
        let r2 = s.reader(&path).unwrap().unwrap();
        let mut c = [0u8; 4];
        r2.read_at(10, &mut c).unwrap();
        r.read_at(20, &mut b[..4]).unwrap();
        assert_eq!(&c[..], &data[10..14]);
        assert_eq!(&b[..4], &data[20..24]);
        // a closed handle: error, no crash
        assert!(s.reader("/proc/self/fd/987654").unwrap().is_err());
        assert!(fd_of("/proc/self/fd/x").is_none() && fd_of("/tmp/a").is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
