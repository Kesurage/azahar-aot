#include <cassert>
#include <iostream>
#include <set>
#include <vector>
#include "video_core/renderer_vulkan/vk_capability_queries.h"

using Types = std::set<vk::StructureType>;

template <typename Root>
Types LinkedTypes(const Root& root) {
    Types types;
    const auto* node = reinterpret_cast<const VkBaseOutStructure*>(root.pNext);
    while (node) {
        assert(types.insert(static_cast<vk::StructureType>(node->sType)).second);
        node = node->pNext;
    }
    return types;
}

struct ExtensionCase {
    const char* name;
    Types features;
    Types properties;
};

int main() {
    using S = vk::StructureType;
    const std::vector<ExtensionCase> cases{
        {"VK_KHR_portability_subset", {S::ePhysicalDevicePortabilitySubsetFeaturesKHR},
         {S::ePhysicalDevicePortabilitySubsetPropertiesKHR}},
        {"VK_EXT_extended_dynamic_state", {S::ePhysicalDeviceExtendedDynamicStateFeaturesEXT}, {}},
        {"VK_KHR_timeline_semaphore", {S::ePhysicalDeviceTimelineSemaphoreFeatures}, {}},
        {"VK_EXT_custom_border_color", {S::ePhysicalDeviceCustomBorderColorFeaturesEXT}, {}},
        {"VK_EXT_index_type_uint8", {S::ePhysicalDeviceIndexTypeUint8FeaturesEXT}, {}},
        {"VK_KHR_index_type_uint8", {S::ePhysicalDeviceIndexTypeUint8FeaturesEXT}, {}},
        {"VK_EXT_robustness2", {S::ePhysicalDeviceRobustness2FeaturesEXT}, {}},
        {"VK_EXT_fragment_shader_interlock", {S::ePhysicalDeviceFragmentShaderInterlockFeaturesEXT}, {}},
        {"VK_EXT_pipeline_creation_cache_control",
         {S::ePhysicalDevicePipelineCreationCacheControlFeatures}, {}},
        {"VK_KHR_fragment_shader_barycentric",
         {S::ePhysicalDeviceFragmentShaderBarycentricFeaturesKHR}, {}},
        {"VK_KHR_driver_properties", {}, {S::ePhysicalDeviceDriverProperties}},
        {"VK_EXT_external_memory_host", {}, {S::ePhysicalDeviceExternalMemoryHostPropertiesEXT}},
    };
    const std::vector<std::string> none;
    auto empty = Vulkan::MakeFeatureQueryChain(VK_API_VERSION_1_1, none);
    assert(LinkedTypes(empty.get()).empty());
    assert(!empty.get<vk::PhysicalDeviceFragmentShaderInterlockFeaturesEXT>()
                .fragmentShaderPixelInterlock);
    assert(LinkedTypes(Vulkan::MakePropertyQueryChain(VK_API_VERSION_1_1, none).get()).empty());

    // Every subset checks the actual outgoing pNext linked list, including nodes after a
    // removed member. Copies/moves must also keep pointers inside the returned chain.
    for (unsigned mask = 0; mask < (1u << cases.size()); ++mask) {
        std::vector<std::string> extensions;
        Types expected_features, expected_properties;
        for (unsigned i = 0; i < cases.size(); ++i) {
            if (mask & (1u << i)) {
                extensions.emplace_back(cases[i].name);
                expected_features.insert(cases[i].features.begin(), cases[i].features.end());
                expected_properties.insert(cases[i].properties.begin(), cases[i].properties.end());
            }
        }
        const auto features = Vulkan::MakeFeatureQueryChain(VK_API_VERSION_1_1, extensions);
        auto copied = features;
        const auto properties = Vulkan::MakePropertyQueryChain(VK_API_VERSION_1_1, extensions);
        assert(LinkedTypes(features.get()) == expected_features);
        assert(LinkedTypes(copied.get()) == expected_features);
        assert(LinkedTypes(properties.get()) == expected_properties);
    }

    const Types timeline{S::ePhysicalDeviceTimelineSemaphoreFeatures};
    const Types driver{S::ePhysicalDeviceDriverProperties};
    assert(LinkedTypes(Vulkan::MakeFeatureQueryChain(VK_API_VERSION_1_2, none).get()) == timeline);
    assert(LinkedTypes(Vulkan::MakePropertyQueryChain(VK_API_VERSION_1_2, none).get()) == driver);
    Types promoted13 = timeline;
    promoted13.insert(S::ePhysicalDevicePipelineCreationCacheControlFeatures);
    assert(LinkedTypes(Vulkan::MakeFeatureQueryChain(VK_API_VERSION_1_3, none).get()) == promoted13);
    Types promoted14 = promoted13;
    promoted14.insert(S::ePhysicalDeviceIndexTypeUint8FeaturesEXT);
    assert(LinkedTypes(Vulkan::MakeFeatureQueryChain(VK_MAKE_API_VERSION(0, 1, 4, 0), none).get()) == promoted14);
    assert(LinkedTypes(Vulkan::MakePropertyQueryChain(VK_API_VERSION_1_3, none).get()) == driver);

    // Reproduce the emulator profile: Vulkan 1.3 without interlock/portability/robustness2.
    const std::vector<std::string> emulator{
        "VK_EXT_extended_dynamic_state", "VK_EXT_custom_border_color", "VK_EXT_index_type_uint8",
        "VK_EXT_fragment_shader_interlock_extra"}; // Exact extension-name matching required.
    Types expected = promoted13;
    expected.insert(S::ePhysicalDeviceExtendedDynamicStateFeaturesEXT);
    expected.insert(S::ePhysicalDeviceCustomBorderColorFeaturesEXT);
    expected.insert(S::ePhysicalDeviceIndexTypeUint8FeaturesEXT);
    assert(LinkedTypes(Vulkan::MakeFeatureQueryChain(VK_API_VERSION_1_3, emulator).get()) == expected);
    std::cout << "Vulkan query chains: 4096 extension subsets, core promotions, copy integrity, "
                 "and emulator regression passed\n";
}
