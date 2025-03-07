/*
 * Copyright (C) 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gapid.views;

import static com.google.common.base.Functions.identity;
import static com.google.gapid.image.Images.noAlpha;
import static com.google.gapid.util.Loadable.MessageType.Error;
import static com.google.gapid.util.Loadable.MessageType.Info;
import static com.google.gapid.widgets.Widgets.createCheckbox;
import static com.google.gapid.widgets.Widgets.createComposite;
import static com.google.gapid.widgets.Widgets.createLazyImageTableColumn;
import static com.google.gapid.widgets.Widgets.createTableColumn;
import static com.google.gapid.widgets.Widgets.createTableViewer;
import static com.google.gapid.widgets.Widgets.filling;
import static com.google.gapid.widgets.Widgets.ifNotDisposed;
import static com.google.gapid.widgets.Widgets.packColumns;
import static com.google.gapid.widgets.Widgets.sorting;
import static com.google.gapid.widgets.Widgets.withAsyncRefresh;
import static com.google.gapid.widgets.Widgets.withLayoutData;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.google.common.collect.Maps;
import com.google.common.primitives.UnsignedLongs;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gapid.models.Capture;
import com.google.gapid.models.CommandStream;
import com.google.gapid.models.CommandStream.CommandIndex;
import com.google.gapid.models.Models;
import com.google.gapid.models.Resources;
import com.google.gapid.models.Settings;
import com.google.gapid.proto.image.Image;
import com.google.gapid.proto.service.Service;
import com.google.gapid.proto.service.api.API;
import com.google.gapid.proto.service.path.Path;
import com.google.gapid.rpc.Rpc;
import com.google.gapid.rpc.Rpc.Result;
import com.google.gapid.rpc.RpcException;
import com.google.gapid.rpc.UiCallback;
import com.google.gapid.util.Loadable;
import com.google.gapid.util.Messages;
import com.google.gapid.util.Strings;
import com.google.gapid.widgets.LoadableImage;
import com.google.gapid.widgets.LoadablePanel;
import com.google.gapid.widgets.LoadingIndicator;
import com.google.gapid.widgets.VisibilityTrackingTableViewer;
import com.google.gapid.widgets.Widgets;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.internal.DPIUtil;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Table;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

/**
 * Displays a list of texture resources of the current capture.
 */
public class TextureList extends Composite
    implements Tab, Capture.Listener, Resources.Listener, CommandStream.Listener {
  protected static final Logger LOG = Logger.getLogger(TextureList.class.getName());

  private final Models models;
  private final LoadablePanel<SashForm> loading;
  private final TableViewer tableViewer;
  protected final ImageProvider imageProvider;
  private final TextureView.TextureWidget textureView;

  public TextureList(Composite parent, Models models, Widgets widgets) {
    super(parent, SWT.NONE);
    this.models = models;

    setLayout(new FillLayout(SWT.VERTICAL));

    loading = LoadablePanel.create(this, widgets, panel -> new SashForm(panel, SWT.VERTICAL));
    SashForm splitter = loading.getContents();

    Composite tableAndOption = createComposite(splitter, new GridLayout(1, false), SWT.BORDER);
    tableViewer = createTableViewer(tableAndOption, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
    tableViewer.getTable().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    imageProvider = new ImageProvider(models, tableViewer, widgets.loading);
    initTextureSelector(tableViewer, imageProvider);
    Composite options =
        createComposite(tableAndOption, filling(new RowLayout(SWT.HORIZONTAL), true, false));
    Button showDeleted = createCheckbox(options, "Show deleted textures", true);

    textureView = withLayoutData(new TextureView.TextureWidget(splitter, false, models, widgets),
        new GridData(SWT.FILL, SWT.FILL, true, true));

    splitter.setWeights(models.settings.getSplitterWeights(Settings.SplitterWeights.Shaders));

    models.capture.addListener(this);
    models.commands.addListener(this);
    models.resources.addListener(this);
    addListener(SWT.Dispose, e -> {
      models.settings.setSplitterWeights(Settings.SplitterWeights.Textures, splitter.getWeights());

      models.capture.removeListener(this);
      models.commands.removeListener(this);
      models.resources.removeListener(this);
      imageProvider.reset();
    });

    ViewerFilter filterDeleted = new ViewerFilter() {
      @Override
      public boolean select(Viewer viewer, Object parentElement, Object element) {
        return !((Data)element).deleted;
      }
    };

    tableViewer.getTable().addListener(SWT.Selection, e -> updateSelection());
    showDeleted.addListener(SWT.Selection, e -> {
      if (showDeleted.getSelection()) {
        tableViewer.removeFilter(filterDeleted);
      } else {
        tableViewer.addFilter(filterDeleted);
      }
    });
  }

  private static void initTextureSelector(TableViewer viewer, ImageProvider imageProvider) {
    viewer.setContentProvider(ArrayContentProvider.getInstance());
    viewer.setLabelProvider(new ViewLabelProvider(imageProvider));

    sorting(viewer,
        createTableColumn(viewer, "Type", Data::getType,
            Comparator.comparing(Data::getType)),
        createLazyImageTableColumn(viewer, "ID", Data::getId, d -> imageProvider.getImage(d), 18,
            (d1, d2) -> UnsignedLongs.compare(d1.getSortId(), d2.getSortId())),
        createTableColumn(viewer, "Name", Data::getLabel,
            Comparator.comparing(Data::getLabel)),
        createTableColumn(viewer, "Width", Data::getWidth,
            Comparator.comparingInt(Data::getSortWidth)),
        createTableColumn(viewer, "Height", Data::getHeight,
            Comparator.comparingInt(Data::getSortHeight)),
        createTableColumn(viewer, "Depth", Data::getDepth,
            Comparator.comparingInt(Data::getSortDepth)),
        createTableColumn(viewer, "Layers", Data::getLayers,
            Comparator.comparingInt(Data::getSortLayers)),
        createTableColumn(viewer, "Levels", Data::getLevels,
            Comparator.comparingInt(Data::getSortLevels)),
        createTableColumn(viewer, "Format", Data::getFormat,
            Comparator.comparing(Data::getFormat)));
  }

  @Override
  public Control getControl() {
    return this;
  }

  @Override
  public void reinitialize() {
    updateTextures();
  }

  @Override
  public void onCaptureLoadingStart(boolean maintainState) {
    loading.showMessage(Info, Messages.LOADING_CAPTURE);
    clear();
  }

  @Override
  public void onCaptureLoaded(Loadable.Message error) {
    if (error != null) {
      loading.showMessage(Error, Messages.CAPTURE_LOAD_FAILURE);
    }
    clear();
  }

  @Override
  public void onResourcesLoaded() {
    updateTextures();
  }

  @Override
  public void onTextureSelected(Service.Resource texture) {
    TableItem[] items = tableViewer.getTable().getItems();

    // Do nothing if texture is already selected.
    int selection = tableViewer.getTable().getSelectionIndex();
    if (texture != null && selection >= 0) {
      Data data = (Data)items[selection].getData();
      if (data.info.getID().equals(texture.getID())) {
        return;
      }
    } else if (texture == null && selection < 0) {
      return;
    }

    if (texture != null) {
      // Find texture in table and select it.
      for (int i = 0; i < items.length; i++) {
        Data data = (Data)(items[i].getData());
        if (data.info.getID().equals(texture.getID())) {
          tableViewer.getTable().setSelection(items[i]);
          break;
        }
      }
      textureView.loadTexture(texture);
    } else {
      tableViewer.setSelection(StructuredSelection.EMPTY);
      textureView.clear();
    }
  }

  @Override
  public void onCommandsSelected(CommandIndex path) {
    updateTextures();
  }

  private void updateTextures() {
    CommandIndex command = models.commands.getSelectedCommands();
    if (!models.resources.isLoaded()) {
      loading.startLoading();
      clear();
    } else if (command == null) {
      loading.showMessage(Info, Messages.SELECT_COMMAND);
      clear();
    } else {
      Table table = tableViewer.getTable();

      // When comparator is reset, the table is refreshed, and early image disposal will introduce null error.
      ViewerComparator comparator = tableViewer.getComparator();
      tableViewer.setComparator(null);

      imageProvider.reset();

      List<Data> textures = models.resources.getResources(Path.ResourceType.Texture).stream()
          .map(r -> new Data(r.resource, r.deleted))
          .collect(toList());
      Map<String, Data> mapped = textures.stream()
          .collect(toMap(Data::getResourceId, identity()));

      tableViewer.setInput(textures);
      tableViewer.setComparator(comparator);

      if (textures.isEmpty()) {
        loading.showMessage(Info, Messages.NO_TEXTURES);
      } else {
        loading.stopLoading();
        onTextureSelected(models.resources.getSelectedTexture());
        Widgets.Refresher refresher = withAsyncRefresh(tableViewer, () -> packColumns(table));

        Rpc.listen(models.resources.loadResources(Path.ResourceType.Texture),
            new UiCallback<Service.MultiResourceData, Map<String, Data.AdditionalInfo>>(
                table, LOG) {
          @Override
          protected Map<String, Data.AdditionalInfo> onRpcThread(
              Result<Service.MultiResourceData> result) throws RpcException, ExecutionException {
            Map<String, Data.AdditionalInfo> infos = Maps.newHashMap();
            for (Map.Entry<String, Service.MultiResourceData.ResourceOrError> e :
                result.get().getResourcesMap().entrySet()) {
              if (e.getValue().hasError()) {
                infos.put(e.getKey(), Data.AdditionalInfo.NULL);
              } else {
                infos.put(e.getKey(), Data.AdditionalInfo.from(e.getValue().getResource()));
              }
            }
            return infos;
          }

          @Override
          protected void onUiThread(Map<String, Data.AdditionalInfo> result) {
            for (Map.Entry<String, Data.AdditionalInfo> e : result.entrySet()) {
              Data data = mapped.get(e.getKey());
              if (data != null) {
                data.imageInfo = e.getValue();
              }
            }
            refresher.refresh();
          }
        });
        Rpc.listen(models.images.getAllTextureThumbnails(command),
            new UiCallback<Service.MultiResourceThumbnail, Map<Data, Image.Info>>(
                table, LOG) {
          @Override
          protected Map<Data, Image.Info> onRpcThread(
              Result<Service.MultiResourceThumbnail> result) throws RpcException, ExecutionException {
            Map<Data, Image.Info> images = Maps.newHashMap();
            for (Map.Entry<String, Service.MultiResourceThumbnail.ThumbnailOrError> e :
                result.get().getImagesMap().entrySet()) {
              if (e.getValue().hasError()) {
                // Ignore thumbnail errors.
              } else {
                Data data = mapped.get(e.getKey());
                if (data != null) {
                  images.put(data, e.getValue().getImage());
                }
              }
            }
            return images;
          }

          @Override
          protected void onUiThread(Map<Data, Image.Info> result) {
            imageProvider.setInfos(result);
            refresher.refresh();
          }
        });
      }
    }
  }

  private void clear() {
    tableViewer.setInput(Collections.emptyList());
    tableViewer.getTable().requestLayout();
    imageProvider.reset();
  }

  private void updateSelection() {
    Service.Resource selectedTexture = null;
    int selection = tableViewer.getTable().getSelectionIndex();
    if (selection >= 0) {
      selectedTexture = ((Data)tableViewer.getElementAt(selection)).info;
    }
    models.resources.selectTexture(selectedTexture);
    textureView.loadTexture(selectedTexture);
  }


  /**
   * Texture metadata.
   */
  private static class Data {
    public final Service.Resource info;
    public final boolean deleted;
    protected AdditionalInfo imageInfo;

    public Data(Service.Resource info, boolean deleted) {
      this.info = info;
      this.deleted = deleted;
      this.imageInfo = AdditionalInfo.NULL;
    }

    public String getType() {
      return imageInfo.getType();
    }

    public String getResourceId() {
      return Strings.toString(info.getID());
    }

    public String getId() {
      return info.getHandle();
    }

    public long getSortId() {
      return info.getOrder();
    }

    public String getLabel() {
      return info.getLabel();
    }

    public String getWidth() {
      return imageInfo.getWidth();
    }

    public int getSortWidth() {
      return imageInfo.level0.getWidth();
    }

    public String getHeight() {
      return imageInfo.getHeight();
    }

    public int getSortHeight() {
      return imageInfo.level0.getHeight();
    }

    public String getDepth() {
      return imageInfo.getDepth();
    }

    public int getSortDepth() {
      return imageInfo.level0.getDepth();
    }

    public String getLayers() {
      return imageInfo.getLayers();
    }

    public int getSortLayers() {
      return imageInfo.layerCount;
    }

    public String getLevels() {
      return imageInfo.getLevels();
    }

    public int getSortLevels() {
      return imageInfo.levelCount;
    }

    public String getFormat() {
      return imageInfo.getFormat();
    }

    private static class AdditionalInfo {
      public static final AdditionalInfo NULL =
          new AdditionalInfo("", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_1D =
          new AdditionalInfo("1D", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_1D_ARRAY =
          new AdditionalInfo("1D Array", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_2D =
          new AdditionalInfo("2D", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_2D_MS =
          new AdditionalInfo("2D Multisampled", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_2D_ARRAY =
          new AdditionalInfo("2D Array", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_2D_MS_ARRAY =
          new AdditionalInfo("2D Multisampled Array", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_3D =
          new AdditionalInfo("3D", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_CUBEMAP =
          new AdditionalInfo("Cubemap", Image.Info.getDefaultInstance(), 0, 0);
      public static final AdditionalInfo NULL_CUBEMAP_ARRAY =
          new AdditionalInfo("Cubemap Array", Image.Info.getDefaultInstance(), 0, 0);

      public final Image.Info level0;
      public final int layerCount;
      public final int levelCount;
      public final String typeLabel;

      public AdditionalInfo(String typeLabel, Image.Info level0, int layerCount, int levelCount) {
        this.level0 = level0;
        this.layerCount = layerCount;
        this.levelCount = levelCount;
        this.typeLabel = typeLabel;
      }

      public static AdditionalInfo from(API.ResourceData data) {
        API.Texture texture = data.getTexture();
        switch (texture.getTypeCase()) {
          case TEXTURE_1D: {
            API.Texture1D t = texture.getTexture1D();
            return (t.getLevelsCount() == 0) ? NULL_1D :
                new AdditionalInfo("1D", t.getLevels(0), 1, t.getLevelsCount());
          }
          case TEXTURE_1D_ARRAY: {
            API.Texture1DArray t = texture.getTexture1DArray();
            return (t.getLayersCount() == 0 || t.getLayers(0).getLevelsCount() == 0) ? NULL_1D_ARRAY :
                new AdditionalInfo("1D Array", t.getLayers(0).getLevels(0), t.getLayersCount(),
                    t.getLayers(0).getLevelsCount());
          }
          case TEXTURE_2D: {
            API.Texture2D t = texture.getTexture2D();
            AdditionalInfo nullInfo = t.getMultisampled() ? NULL_2D_MS : NULL_2D;
            return (t.getLevelsCount() == 0) ? nullInfo :
                new AdditionalInfo(nullInfo.typeLabel, t.getLevels(0), 1, t.getLevelsCount());
          }
          case TEXTURE_2D_ARRAY: {
            API.Texture2DArray t = texture.getTexture2DArray();
            AdditionalInfo nullInfo = t.getMultisampled() ? NULL_2D_MS_ARRAY : NULL_2D_ARRAY;
            return (t.getLayersCount() == 0 || t.getLayers(0).getLevelsCount() == 0) ? nullInfo :
                new AdditionalInfo(nullInfo.typeLabel, t.getLayers(0).getLevels(0), t.getLayersCount(),
                    t.getLayers(0).getLevelsCount());
          }
          case TEXTURE_3D: {
            API.Texture3D t = texture.getTexture3D();
            return (t.getLevelsCount() == 0) ? NULL_3D :
                new AdditionalInfo("3D", t.getLevels(0), 1, t.getLevelsCount());
          }
          case CUBEMAP: {
            API.Cubemap c = texture.getCubemap();
            return (c.getLevelsCount() == 0) ? NULL_CUBEMAP :
                new AdditionalInfo("Cubemap", c.getLevels(0).getNegativeX(), 1, c.getLevelsCount());
          }
          case CUBEMAP_ARRAY: {
            return NULL_CUBEMAP_ARRAY; // TODO
          }
          default:
            return NULL;
        }
      }

      public String getType() {
        return typeLabel;
      }

      public String getWidth() {
        return (layerCount == 0) ? "" : String.valueOf(level0.getWidth());
      }

      public String getHeight() {
        return (layerCount == 0) ? "" : String.valueOf(level0.getHeight());
      }

      public String getDepth() {
        return (layerCount == 0) ? "" : String.valueOf(level0.getDepth());
      }

      public String getFormat() {
        return (layerCount == 0) ? "" : level0.getFormat().getName();
      }

      public String getLayers() {
        return (layerCount == 0) ? "" : String.valueOf(layerCount);
      }

      public String getLevels() {
        return (layerCount == 0) ? "" : String.valueOf(levelCount);
      }
    }
  }

  /**
   * Image provider for the texture selector.
   */
  private static class ImageProvider implements LoadingIndicator.Repaintable {
    private static final int SIZE = DPIUtil.autoScaleUp(18);

    private final Models models;
    private final TableViewer viewer;
    private final LoadingIndicator loading;
    private final Map<Data, Image.Info> infos = Maps.newIdentityHashMap();
    private final Map<Data, LoadableImage> images = Maps.newIdentityHashMap();

    public ImageProvider(Models models, TableViewer viewer, LoadingIndicator loading) {
      this.models = models;
      this.viewer = viewer;
      this.loading = loading;
    }

    public void setInfos(Map<Data, Image.Info> infos) {
      this.infos.clear();
      this.infos.putAll(infos);
    }

    public void load(Data data) {
      LoadableImage image = images.get(data);
      if (image != null) {
        image.load();
      }
    }

    public void unload(Data data) {
      LoadableImage image = images.get(data);
      if (image != null) {
        image.unload();
      }
    }

    public org.eclipse.swt.graphics.Image getImage(Data data) {
      if (infos.isEmpty()) {
        return null;
      }
      return getLoadableImage(data).getImage();
    }

    @Override
    public void repaint() {
      ifNotDisposed(viewer.getControl(), () -> viewer.getControl().redraw());
    }

    private LoadableImage getLoadableImage(Data data) {
      LoadableImage image = images.get(data);
      if (image == null) {
        Image.Info info = infos.get(data);
        if (info == null) {
          image = LoadableImage.loadedImage(viewer.getTable(), null);
        } else {
          image = LoadableImage.newBuilder(loading)
              .small()
              .forImageData(() -> loadImage(info))
              .onErrorReturnNull()
              .build(viewer.getTable(), this);
        }
        images.put(data, image);
      }
      return image;
    }

    private ListenableFuture<ImageData> loadImage(Image.Info info) {
      return noAlpha(models.images.getThumbnail(info, SIZE));
    }

    public void reset() {
      for (LoadableImage image : images.values()) {
        image.dispose();
      }
      images.clear();
      infos.clear();
    }
  }

  private static class ViewLabelProvider extends LabelProvider
      implements VisibilityTrackingTableViewer.Listener {
    private final ImageProvider imageProvider;

    public ViewLabelProvider(ImageProvider imageProvider) {
      this.imageProvider = imageProvider;
    }

    @Override
    public void onShow(TableItem item) {
      Object element = item.getData();
      if (element instanceof Data) {
        imageProvider.load((Data)element);
      }
    }

    @Override
    public void onHide(TableItem item) {
      Object element = item.getData();
      if (element instanceof Data) {
        imageProvider.unload((Data)element);
      }
    }
  }
}
