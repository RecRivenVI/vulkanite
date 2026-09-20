#include <jni.h>
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_vk.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_helpers_dlssd_vk.h>
#include <array>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <set>

namespace {
constexpr char ProjectId[] = "816bdc98-8abe-4dd0-a8ce-89fbbbc03be2";
std::mutex mutex;
VkDevice activeDevice{};
int users = 0;
void checked(NVSDK_NGX_Result result, const char* stage) {
    if (NVSDK_NGX_FAILED(result)) {
        char text[160]; std::snprintf(text, sizeof(text), "%s failed: NGX 0x%08X", stage, unsigned(result));
        throw std::runtime_error(text);
    }
}
void error(JNIEnv* env, const std::exception& exception) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), exception.what());
}
std::wstring path(JNIEnv* env, jstring value) {
    const jchar* chars = env->GetStringChars(value, nullptr);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars); return result;
}
void NVSDK_CONV log(const char* text, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature) {
    std::fprintf(stderr, "[Vulkanite/NGX] %s\n", text ? text : "");
}
struct Session {
    NVSDK_NGX_Parameter* parameters{};
    NVSDK_NGX_Handle* feature{};
    int mode{}, quality{}, width{}, height{}, outWidth{}, outHeight{};
};
}

extern "C" JNIEXPORT jint JNICALL Java_me_cortex_vulkanite_client_rendering_dlss_NgxBridge_bridgeVersion(JNIEnv*,jclass) { return 2; }

extern "C" JNIEXPORT jobjectArray JNICALL Java_me_cortex_vulkanite_client_rendering_dlss_NgxBridge_requirements(
        JNIEnv* env, jclass, jlong instance, jlong physical, jstring directory) {
    std::lock_guard guard(mutex);
    try {
        auto root = path(env, directory); const wchar_t* paths[] = {root.c_str()};
        NVSDK_NGX_FeatureCommonInfo common{}; common.PathListInfo = {paths, 1};
        NVSDK_NGX_FeatureDiscoveryInfo info{};
        info.SDKVersion = NVSDK_NGX_Version_API;
        info.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
        info.Identifier.v.ProjectDesc = {ProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "Vulkanite-26.3"};
        info.ApplicationDataPath = root.c_str(); info.FeatureInfo = &common;
        std::set<std::string> extensions;
        for (auto feature : {NVSDK_NGX_Feature_SuperSampling, NVSDK_NGX_Feature_RayReconstruction}) {
            info.FeatureID = feature;
            uint32_t count{}; VkExtensionProperties* list{};
            if (instance) checked(NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
                    reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkPhysicalDevice>(physical), &info, &count, &list), "device requirements");
            else checked(NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info, &count, &list), "instance requirements");
            for (uint32_t i=0; i<count; i++) extensions.insert(list[i].extensionName);
        }
        auto result = env->NewObjectArray(jsize(extensions.size()), env->FindClass("java/lang/String"), nullptr);
        int index=0;
        for (const auto& extension : extensions) {
            auto value=env->NewStringUTF(extension.c_str()); env->SetObjectArrayElement(result,index++,value); env->DeleteLocalRef(value);
        }
        return result;
    } catch(const std::exception& exception) { error(env, exception); return nullptr; }
}

extern "C" JNIEXPORT jlong JNICALL Java_me_cortex_vulkanite_client_rendering_dlss_NgxBridge_open(
        JNIEnv* env, jclass, jlong instance, jlong physical, jlong device, jstring directory, jint mode, jint quality, jint outWidth, jint outHeight) {
    std::lock_guard guard(mutex);
    bool initialized=false;
    auto session=std::make_unique<Session>();
    try {
        if (mode<1 || mode>2 || quality<0 || quality>5 || outWidth<1 || outHeight<1) throw std::runtime_error("Invalid reconstruction request");
        auto vkDevice=reinterpret_cast<VkDevice>(device);
        if (users && activeDevice!=vkDevice) throw std::runtime_error("NGX bridge supports one Vulkan device");
        if (!users) {
            auto root=path(env,directory); const wchar_t* paths[]={root.c_str()};
            NVSDK_NGX_FeatureCommonInfo common{}; common.PathListInfo={paths,1};
            common.LoggingInfo.LoggingCallback=&log; common.LoggingInfo.MinimumLoggingLevel=NVSDK_NGX_LOGGING_LEVEL_ON;
            checked(NVSDK_NGX_VULKAN_Init_with_ProjectID(ProjectId,NVSDK_NGX_ENGINE_TYPE_CUSTOM,"Vulkanite-26.3",root.c_str(),
                    reinterpret_cast<VkInstance>(instance),reinterpret_cast<VkPhysicalDevice>(physical),vkDevice,
                    vkGetInstanceProcAddr,vkGetDeviceProcAddr,&common),"initialize");
            activeDevice=vkDevice; initialized=true;
        }
        checked(NVSDK_NGX_VULKAN_GetCapabilityParameters(&session->parameters),"capabilities");
        int available=0;
        checked(session->parameters->Get(mode==2 ? NVSDK_NGX_Parameter_SuperSamplingDenoising_Available : NVSDK_NGX_Parameter_SuperSampling_Available,&available),"feature availability");
        if (!available) throw std::runtime_error("Requested DLSS feature unavailable on this device/driver");
        unsigned width{},height{},maxW{},maxH{},minW{},minH{}; float sharpness{};
        checked(NGX_DLSS_GET_OPTIMAL_SETTINGS(session->parameters,outWidth,outHeight,NVSDK_NGX_PerfQuality_Value(quality),
                &width,&height,&maxW,&maxH,&minW,&minH,&sharpness),"optimal settings");
        session->mode=mode; session->quality=quality; session->width=int(width); session->height=int(height);
        session->outWidth=outWidth; session->outHeight=outHeight;
        users++;
        return reinterpret_cast<jlong>(session.release());
    } catch(const std::exception& exception) {
        if (session->parameters) NVSDK_NGX_VULKAN_DestroyParameters(session->parameters);
        if (initialized) { NVSDK_NGX_VULKAN_Shutdown1(activeDevice); activeDevice={}; }
        error(env,exception); return 0;
    }
}

extern "C" JNIEXPORT jintArray JNICALL Java_me_cortex_vulkanite_client_rendering_dlss_NgxBridge_dimensions(JNIEnv* env,jclass,jlong handle) {
    auto* s=reinterpret_cast<Session*>(handle); jint values[]={s->width,s->height};
    auto result=env->NewIntArray(2); env->SetIntArrayRegion(result,0,2,values); return result;
}

extern "C" JNIEXPORT void JNICALL Java_me_cortex_vulkanite_client_rendering_dlss_NgxBridge_create(JNIEnv* env,jclass,jlong handle,jlong command) {
    std::lock_guard guard(mutex);
    auto* s=reinterpret_cast<Session*>(handle);
    try {
        int flags=NVSDK_NGX_DLSS_Feature_Flags_IsHDR | NVSDK_NGX_DLSS_Feature_Flags_MVLowRes
                | NVSDK_NGX_DLSS_Feature_Flags_DepthInverted | NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
        if (s->mode==2) {
            NVSDK_NGX_DLSSD_Create_Params p{};
            p.InWidth=s->width; p.InHeight=s->height; p.InTargetWidth=s->outWidth; p.InTargetHeight=s->outHeight;
            p.InPerfQualityValue=NVSDK_NGX_PerfQuality_Value(s->quality); p.InFeatureCreateFlags=flags;
            p.InRoughnessMode=NVSDK_NGX_DLSS_Roughness_Mode_Packed; p.InUseHWDepth=NVSDK_NGX_DLSS_Depth_Type_HW;
            checked(NGX_VULKAN_CREATE_DLSSD_EXT1(activeDevice,reinterpret_cast<VkCommandBuffer>(command),1,1,&s->feature,s->parameters,&p),"create RR");
        } else {
            NVSDK_NGX_DLSS_Create_Params p{};
            p.Feature.InWidth=s->width; p.Feature.InHeight=s->height; p.Feature.InTargetWidth=s->outWidth; p.Feature.InTargetHeight=s->outHeight;
            p.Feature.InPerfQualityValue=NVSDK_NGX_PerfQuality_Value(s->quality); p.InFeatureCreateFlags=flags;
            checked(NGX_VULKAN_CREATE_DLSS_EXT1(activeDevice,reinterpret_cast<VkCommandBuffer>(command),1,1,&s->feature,s->parameters,&p),"create SR");
        }
    } catch(const std::exception& exception) { error(env,exception); }
}

extern "C" JNIEXPORT void JNICALL Java_me_cortex_vulkanite_client_rendering_dlss_NgxBridge_evaluate(
        JNIEnv* env,jclass,jlong handle,jlong command,jlongArray descriptors,jfloatArray constants) {
    std::lock_guard guard(mutex);
    try {
        auto* s=reinterpret_cast<Session*>(handle);
        if (env->GetArrayLength(descriptors)!=45 || env->GetArrayLength(constants)!=37) throw std::runtime_error("Bad DLSS ABI payload");
        std::array<jlong,45> data; std::array<float,37> c;
        env->GetLongArrayRegion(descriptors,0,45,data.data()); env->GetFloatArrayRegion(constants,0,37,c.data());
        std::array<NVSDK_NGX_Resource_VK,9> images;
        VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        for (int i=0;i<9;i++) images[i]=NVSDK_NGX_Create_ImageView_Resource_VK(
                VkImageView(data[i*5+1]),VkImage(data[i*5]),range,VkFormat(data[i*5+2]),unsigned(data[i*5+3]),unsigned(data[i*5+4]),i==8);
        if (s->mode==2) {
            NVSDK_NGX_VK_DLSSD_Eval_Params p{};
            p.pInColor=&images[0]; p.pInDepth=&images[1]; p.pInMotionVectors=&images[2];
            p.pInDiffuseAlbedo=&images[3]; p.pInSpecularAlbedo=&images[4]; p.pInNormals=&images[5];
            p.pInSpecularHitDistance=&images[6]; p.pInBiasCurrentColorMask=&images[7]; p.pInOutput=&images[8];
            p.InJitterOffsetX=c[0]; p.InJitterOffsetY=c[1]; p.InReset=int(c[2]); p.InFrameTimeDeltaInMsec=c[3];
            p.InMVScaleX=p.InMVScaleY=1; p.InPreExposure=c[36]; p.InExposureScale=1;
            p.InRenderSubrectDimensions={unsigned(s->width),unsigned(s->height)};
            p.pInWorldToViewMatrix=&c[4]; p.pInViewToClipMatrix=&c[20];
            checked(NGX_VULKAN_EVALUATE_DLSSD_EXT(reinterpret_cast<VkCommandBuffer>(command),s->feature,s->parameters,&p),"evaluate RR");
        } else {
            NVSDK_NGX_VK_DLSS_Eval_Params p{};
            p.Feature.pInColor=&images[0]; p.Feature.pInOutput=&images[8]; p.pInDepth=&images[1]; p.pInMotionVectors=&images[2];
            p.pInBiasCurrentColorMask=&images[7]; p.InJitterOffsetX=c[0]; p.InJitterOffsetY=c[1]; p.InReset=int(c[2]);
            p.InFrameTimeDeltaInMsec=c[3]; p.InMVScaleX=p.InMVScaleY=1; p.InPreExposure=c[36]; p.InExposureScale=1;
            p.InRenderSubrectDimensions={unsigned(s->width),unsigned(s->height)};
            checked(NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(command),s->feature,s->parameters,&p),"evaluate SR");
        }
    } catch(const std::exception& exception) { error(env,exception); }
}

extern "C" JNIEXPORT void JNICALL Java_me_cortex_vulkanite_client_rendering_dlss_NgxBridge_close(JNIEnv*,jclass,jlong handle) {
    std::lock_guard guard(mutex); auto* s=reinterpret_cast<Session*>(handle);
    if (!s) return;
    if (s->feature) NVSDK_NGX_VULKAN_ReleaseFeature(s->feature);
    if (s->parameters) NVSDK_NGX_VULKAN_DestroyParameters(s->parameters);
    delete s;
    if (--users==0) { NVSDK_NGX_VULKAN_Shutdown1(activeDevice); activeDevice={}; }
}
