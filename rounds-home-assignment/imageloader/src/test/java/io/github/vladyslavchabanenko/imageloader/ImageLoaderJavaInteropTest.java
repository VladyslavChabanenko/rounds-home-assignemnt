package io.github.vladyslavchabanenko.imageloader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Calls the whole public API from plain Java, with no coroutine types. The image view is never
 * laid out, so no download starts and each call leaves the placeholder showing.
 */
@RunWith(RobolectricTestRunner.class)
public class ImageLoaderJavaInteropTest {
    private static final String URL = "https://example.test/image.jpg";

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void getReturnsTheSameInstanceTwice() {
        assertSame(ImageLoader.get(context), ImageLoader.get(context));
    }

    @Test
    public void loadWithResourceIdsShowsThePlaceholder() {
        ImageLoader loader = ImageLoader.get(context);
        ImageView view = new ImageView(context);

        loader.load(URL, view, android.R.drawable.ic_menu_gallery);
        assertNotNull(view.getDrawable());

        view.setImageDrawable(null);
        loader.load(URL, view, android.R.drawable.ic_menu_gallery, android.R.drawable.ic_delete);
        assertNotNull(view.getDrawable());
    }

    @Test
    public void loadWithDrawablesShowsThePlaceholder() {
        ImageLoader loader = ImageLoader.get(context);
        ImageView view = new ImageView(context);
        Drawable placeholder = new ColorDrawable(Color.GRAY);
        Drawable error = new ColorDrawable(Color.RED);

        loader.load(URL, view, placeholder);
        assertSame(placeholder, view.getDrawable());

        view.setImageDrawable(null);
        loader.load(URL, view, placeholder, error);
        assertSame(placeholder, view.getDrawable());

        view.setImageDrawable(null);
        loader.load(URL, view, placeholder, null);
        assertSame(placeholder, view.getDrawable());
    }

    @Test
    public void invalidateCacheReturnsWithoutThrowing() {
        ImageLoader.get(context).invalidateCache();
    }
}
