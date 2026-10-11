use std::fs;
use std::path::{Path, PathBuf};

use fontxml::{Document, Root};
use mount::MountTable;
use serde::{Deserialize, Serialize};

/// The font configuration documents of the system that the engine generates.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum DocumentKind {
    /// `/system/etc/fonts.xml`: the configuration of Android 14 and earlier, and the legacy
    /// configuration that Android 15 and later keep for applications that parse it themselves.
    FontsXML,
    /// `/system/etc/font_fallback.xml`: the configuration of Android 15 and later.
    FontFallbackXML,
    /// `/product/etc/fonts_customization.xml`: the OEM customization.
    FontsCustomizationXML,
}

impl DocumentKind {
    pub const ALL: [DocumentKind; 3] = [DocumentKind::FontsXML, DocumentKind::FontFallbackXML, DocumentKind::FontsCustomizationXML];

    /// The marker comment appended to every generated document.
    pub const MARKER: &'static str = "<!-- typeface-droplet: generated file -->";

    pub fn path(&self) -> &'static Path {
        Path::new(match self {
            DocumentKind::FontsXML => "/system/etc/fonts.xml",
            DocumentKind::FontFallbackXML => "/system/etc/font_fallback.xml",
            DocumentKind::FontsCustomizationXML => "/product/etc/fonts_customization.xml",
        })
    }

    pub fn file_name(&self) -> &'static str {
        match self {
            DocumentKind::FontsXML => "fonts.xml",
            DocumentKind::FontFallbackXML => "font_fallback.xml",
            DocumentKind::FontsCustomizationXML => "fonts_customization.xml",
        }
    }

    /// The path as it appears in mountinfo, with symbolic links resolved (on some devices
    /// `/product` is a link to `/system/product`).
    pub fn mount_point(&self) -> PathBuf {
        fs::canonicalize(self.path()).unwrap_or_else(|_| self.path().to_path_buf())
    }

    /// Whether the system shows a generated document at the path of this document: something
    /// is mounted there, and it carries the marker of generated documents. Documents generated
    /// by any store or version of the engine are recognized.
    pub fn shows_generated(&self, table: &MountTable) -> bool {
        table.top(&self.mount_point()).is_some() && fs::read_to_string(self.path()).is_ok_and(|text| text.contains(DocumentKind::MARKER))
    }

    /// The directory that font file names of the document are relative to.
    pub fn font_directory(&self) -> &'static Path {
        Path::new(match self {
            DocumentKind::FontsXML | DocumentKind::FontFallbackXML => "/system/fonts",
            DocumentKind::FontsCustomizationXML => "/product/fonts",
        })
    }

    pub fn root(&self) -> Root {
        match self {
            DocumentKind::FontsXML | DocumentKind::FontFallbackXML => Root::FamilySet,
            DocumentKind::FontsCustomizationXML => Root::FontsModification,
        }
    }

    /// Whether the parser of this document understands the `supportedAxes` attribute.
    /// fonts.xml is also read by parsers outside the framework (such as Skia's), which do not,
    /// so it never uses it.
    pub fn supports_axes(&self, api: u32) -> bool {
        match self {
            DocumentKind::FontsXML => false,
            DocumentKind::FontFallbackXML => true,
            DocumentKind::FontsCustomizationXML => api >= 35,
        }
    }

    /// Whether fallback families can be inserted into this document.
    pub fn allows_fallback(&self) -> bool {
        self.root() == Root::FamilySet
    }

    /// The values of the root `version` attribute this engine has been verified with.
    /// `None` stands for a document without the attribute.
    pub fn known_versions(&self) -> &'static [Option<&'static str>] {
        match self {
            DocumentKind::FontsXML | DocumentKind::FontFallbackXML => &[None, Some("23")],
            DocumentKind::FontsCustomizationXML => &[None, Some("1")],
        }
    }

    /// Checks that `document` is a document of this kind with a known version.
    pub fn check(&self, document: &Document, allow_unknown_versions: bool) -> Result<(), String> {
        if document.root != self.root() {
            return Err(format!("the root element is <{}>, not <{}>", document.root.element_name(), self.root().element_name()));
        }
        let version = document.attributes.get("version");
        if !allow_unknown_versions && !self.known_versions().contains(&version) {
            return Err(format!(
                "version {} of <{}> is not known to this engine (allowUnknownVersions accepts it)",
                version.unwrap_or("(none)"),
                self.root().element_name()
            ));
        }
        Ok(())
    }
}
