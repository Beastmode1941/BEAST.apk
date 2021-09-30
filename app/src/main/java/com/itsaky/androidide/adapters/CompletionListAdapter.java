package com.itsaky.androidide.adapters;

import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.blankj.utilcode.util.ThreadUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.itsaky.androidide.R;
import com.itsaky.androidide.app.StudioApp;
import com.itsaky.androidide.databinding.LayoutCompletionItemBinding;
import com.itsaky.androidide.models.CompletionItemWrapper;
import com.itsaky.androidide.models.CompletionListItem;
import com.itsaky.androidide.models.SuggestItem;
import com.itsaky.androidide.utils.Logger;
import com.itsaky.androidide.utils.TypefaceUtils;
import com.itsaky.apiinfo.ApiInfo;
import com.itsaky.apiinfo.models.ClassInfo;
import com.itsaky.apiinfo.models.FieldInfo;
import com.itsaky.apiinfo.models.Info;
import com.itsaky.apiinfo.models.MethodInfo;
import io.github.rosemoe.editor.widget.EditorCompletionAdapter;
import java.util.Locale;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

public class CompletionListAdapter extends EditorCompletionAdapter {
    
    private static final Logger LOG = Logger.instance("CompletionListAdapter");
    
    @Override
    public int getItemHeight() {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40, Resources.getSystem().getDisplayMetrics());
    }

    @Override
    protected View getView(int position, View convertView, ViewGroup parent, boolean isCurrentCursorPosition) {
        LayoutCompletionItemBinding binding = LayoutCompletionItemBinding.inflate(LayoutInflater.from(getContext()), parent, false);

		CompletionItem item = getItem(position);
		String label = item.getLabel(), desc = item.getDetail(), type = item.getKind().toString();
		String header = type == null || type.length() <= 0 ? "O" : String.valueOf(type.toString().charAt(0));
		
        binding.completionIconText.setText(header);
        binding.completionLabel.setText(label);
        binding.completionType.setText(type);
        binding.completionDetail.setText(desc);
        binding.completionIconText.setTypeface(TypefaceUtils.jetbrainsMono(), Typeface.BOLD);
		if (desc == null || desc.isEmpty())
			binding.completionDetail.setVisibility(View.GONE);

        if (isCurrentCursorPosition)
            binding.getRoot().setBackgroundColor(ContextCompat.getColor(getContext(), R.color.completionList_backgroundSelected));
        else binding.getRoot().setBackgroundColor(ContextCompat.getColor(getContext(), R.color.completionList_background));

        binding.completionApiInfo.setVisibility(View.GONE);
        
        if (item != null) {
            showApiInfoIfNeeded(item, binding.completionApiInfo);
        }
        
        binding.getRoot().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            
            ViewGroup.LayoutParams p = binding.completionIconText.getLayoutParams();
            p.height = binding.getRoot().getHeight();
            binding.completionIconText.setLayoutParams(p);
        });

        return binding.getRoot();
    }

    private void showApiInfoIfNeeded(final CompletionItem item, final TextView completionApiInfo) {
        
        new Thread(() -> {
            final ApiInfo info = StudioApp.getInstance().getApiInfo();
            boolean hasRead = info != null && info.hasRead();
            boolean isValid = isValidForApiVersion(item);
            
            if (hasRead && isValid) {

                JsonElement element = new Gson().toJsonTree(item.getData());
                if (element == null || !element.isJsonObject()) return;
                JsonObject data = element.getAsJsonObject();
                if (!data.has("className")) return;
                
                final String className = data.get("className").getAsString();
                CompletionItemKind kind = item.getKind();
                
                ClassInfo clazz = info.getClassByName(className);
                if(clazz == null) return;
                
                Info apiInfo = clazz;

                /**
                 * If this Info is not a class info, find the right member
                 */
                if (kind == CompletionItemKind.Method
                    && data.has("erasedParameterTypes")
                    && data.has("memberName")) {
                    JsonElement erasedParameterTypesElement = data.get("erasedParameterTypes");
                    if(erasedParameterTypesElement.isJsonArray()) {
                        String simpleName = data.get("memberName").getAsString();
                        JsonArray erasedParameterTypes = erasedParameterTypesElement.getAsJsonArray();
                        String[] paramTypes = new String[erasedParameterTypes.size()];
                        for(int i=0;i<erasedParameterTypes.size();i++) {
                            paramTypes[i] = erasedParameterTypes.get(i).getAsString();
                        }
                        
                        MethodInfo method = clazz.getMethod(simpleName, paramTypes);
                        
                        if(method != null) {
                            apiInfo = method;
                        }
                    }
                } else if(kind == CompletionItemKind.Field
                          && data.has("memberName")) {
                    String simpleName = data.get("memberName").getAsString();
                    FieldInfo field = clazz.getFieldByName(simpleName);
                    
                    if(field != null) {
                        apiInfo = field;
                    }
                }

                final StringBuilder infoBuilder = new StringBuilder();
                if (apiInfo != null && apiInfo.since > 1) {
                    infoBuilder.append(completionApiInfo.getContext().getString(R.string.msg_api_info_since, apiInfo.since));
                    infoBuilder.append("\n");
                }

                if (apiInfo != null && apiInfo.removed > 0) {
                    infoBuilder.append(completionApiInfo.getContext().getString(R.string.msg_api_info_removed, apiInfo.removed));
                    infoBuilder.append("\n");
                }

                if (apiInfo != null && apiInfo.deprecated > 0) {
                    infoBuilder.append(completionApiInfo.getContext().getString(R.string.msg_api_info_deprecated, apiInfo.deprecated));
                    infoBuilder.append("\n");
                }
                
                ThreadUtils.runOnUiThread(() -> {
                    if(infoBuilder.toString().trim().length() > 0) {
                        completionApiInfo.setText(infoBuilder.toString().trim());
                        completionApiInfo.setVisibility(View.VISIBLE);
                    } else completionApiInfo.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private boolean isValidForApiVersion(CompletionItem item) {
        if (item == null) return false;
        final CompletionItemKind type = item.getKind();
        JsonElement element = new Gson().toJsonTree(item.getData());
        if (
        
        /**
         * These represent a class type
         */
        (type == CompletionItemKind.Class
            || type == CompletionItemKind.Interface
            || type == CompletionItemKind.Enum
            
        /**
         * These represent a method type
         */
            || type == CompletionItemKind.Method
            || type == CompletionItemKind.Constructor
            
        /**
         * A field type
         */
            || type == CompletionItemKind.Field)
            
            && element != null && element.isJsonObject()) {
            JsonObject data = element.getAsJsonObject();
            return data.has("className");
        }
        return false;
    }
}
