package de.notenfuchs.web;

import io.quarkus.qute.TemplateGlobal;

/**
 * {@code ?v={assetVersion}} on CSS and JS links. Quarkus lets browsers keep static files for 24 hours;
 * after an update, old CSS next to new pages would show broken pages and leave "Noten verbergen" without effect.
 */
@TemplateGlobal
public class AssetVersion {

    // Changes with every start, so an update always means new URLs; a restart costs one download.
    static final String assetVersion = Long.toString(System.currentTimeMillis(), 36);
}
